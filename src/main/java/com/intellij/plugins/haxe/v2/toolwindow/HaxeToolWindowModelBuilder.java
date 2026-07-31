package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.haxelib.HaxelibInstalledIndex;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.toolwindow.tree.*;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.*;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-side model for the Haxe tool window: scans containers (modules and the
 * project root), merges detected and manually added build files, parses each
 * and resolves the per-container environment, compile-command and server rows.
 * Produces plain data - turning it into Swing nodes stays with the panel.
 */
final class HaxeToolWindowModelBuilder {

  private final Project project;
  // fired when a background lime evaluation lands, so the owner can re-scan
  private final Runnable onLimeEvaluationReady;

  HaxeToolWindowModelBuilder(@NotNull Project project, @NotNull Runnable onLimeEvaluationReady) {
    this.project = project;
    this.onLimeEvaluationReady = onLimeEvaluationReady;
  }

  record FileEntry(HaxeBuildFile buildFile, HaxeBuildFileInfo info, boolean manual, List<ActionNode> actions) {
  }

  /** A build-file container: a module, or the project root for files outside every module. */
  record ContainerEntry(String id, String displayName, boolean projectRoot,
                        List<FileEntry> files, @Nullable String activePath,
                        EnvironmentData environment,
                        EnvCompileCommandNode compileCommand,
                        CompilationServerNode server) {
  }

  /** The container's environment as shown in the tree, resolved during the scan read action. */
  record EnvironmentData(String sdkDisplay, boolean sdkMissing, String languageLevelDisplay,
                         List<EnvDefineNode> defines, Set<String> activeBuildFileDefines) {
  }

  /** A container before global active-file resolution. */
  private record RawContainer(String id, String displayName, boolean projectRoot, List<FileEntry> files) {
  }

  /** Builds the container model the tree renders. Call under a read action. */
  @NotNull
  List<ContainerEntry> build() {
    List<RawContainer> rawContainers = collectRawContainers();

    // The active build file is a PROJECT-wide singleton: the IDE keeps one parse tree
    // per file, so conditional compilation can only follow one build configuration.
    List<String> allPaths = rawContainers.stream()
      .flatMap(raw -> raw.files().stream())
      .map(entry -> entry.buildFile().file().getPath())
      .toList();

    String activePath = HaxeActiveBuildFileStore.getInstance(project).resolveActivePath(allPaths);
    FileEntry activeEntry = rawContainers.stream()
      .flatMap(raw -> raw.files().stream())
      .filter(entry -> entry.buildFile().file().getPath().equals(activePath))
      .findFirst()
      .orElse(null);

    Set<String> activeDefines = activeEntry == null ? Set.of()
                                                    : activeEntry.info().defines().stream()
                                                        .map(HaxeBuildFileInfo.HaxeDefine::name)
                                                        .collect(Collectors.toSet());

    List<ContainerEntry> containers = new ArrayList<>();
    for (RawContainer raw : rawContainers) {
      EnvCompileCommandNode compileCommand = compileCommandNode(raw.id(), raw.files());
      EnvironmentData environment = buildEnvironmentData(raw.id(), activeDefines);
      CompilationServerNode server = compilationServerNode(raw.id(), compileCommand.connectEligible());
      ContainerEntry container = new ContainerEntry(raw.id(), raw.displayName(), raw.projectRoot(), raw.files(),
                                                    activePath, environment, compileCommand, server);
      containers.add(container);
    }
    return containers;
  }

  /**
   * Haxelib's selected version per installed library (lower-cased name, value may be null
   * when no version is selected), or null when haxelib is unavailable. Runs an external
   * process - call outside read actions.
   */
  @Nullable
  static Map<String, String> fetchInstalledLibraryVersions(@NotNull Project project) {
    Sdk sdk = HaxeToolPathResolver.findConfiguredSdk(project);
    VirtualFile workDir = ProjectUtil.guessProjectDir(project);
    if (sdk == null || workDir == null) return null;

    HaxelibInstalledIndex index = HaxelibInstalledIndex.fetchFromHaxelib(sdk, workDir);
    Map<String, String> selectedByName = new HashMap<>();
    for (String name : index.getInstalledLibraries()) {
      selectedByName.put(name.toLowerCase(Locale.ROOT), index.getSelectedVersion(name));
    }
    return selectedByName;
  }

  @NotNull
  private List<RawContainer> collectRawContainers() {
    List<RawContainer> rawContainers = new ArrayList<>();

    // The module whose content root is the project base dir IS the project - its build
    // files belong to the project node, and it gets no module row of its own.
    Module rootModule = findProjectRootModule();
    String rootContainerId = rootModule != null ? rootModule.getName() : HaxeContainers.PROJECT_ROOT_CONTAINER;
    List<HaxeBuildFile> rootDetected = new ArrayList<>(HaxeBuildFileScanner.scanProjectRoot(project));
    if (rootModule != null) {
      rootDetected.addAll(HaxeBuildFileScanner.scan(rootModule));
    }
    List<FileEntry> rootFiles = mergeAndInspect(rootContainerId, rootDetected);
    // Even with no files yet, a root module means the project node gets its Build group
    // so files can be added manually.
    if (!rootFiles.isEmpty() || rootModule != null) {
      rawContainers.add(new RawContainer(rootContainerId, project.getName(), true, rootFiles));
    }

    Module[] modules = ModuleManager.getInstance(project).getModules();
    Arrays.sort(modules, Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
    for (Module module : modules) {
      if (module.equals(rootModule)) continue;
      List<FileEntry> files = mergeAndInspect(module.getName(), HaxeBuildFileScanner.scan(module));
      rawContainers.add(new RawContainer(module.getName(), module.getName(), false, files));
    }
    return rawContainers;
  }

  @Nullable
  private Module findProjectRootModule() {
    VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    return baseDir == null ? null : ProjectFileIndex.getInstance(project).getModuleForFile(baseDir);
  }

  /** Combines auto-detected files with manual additions, drops hidden ones, and parses each file. */
  @NotNull
  private List<FileEntry> mergeAndInspect(@NotNull String containerId, @NotNull List<HaxeBuildFile> detected) {
    HaxeBuildFilesStore filesStore = HaxeBuildFilesStore.getInstance(project);
    Set<String> manualPaths = new LinkedHashSet<>(filesStore.getAddedPaths(containerId));

    Map<String, HaxeBuildFile> byPath = new LinkedHashMap<>();
    for (HaxeBuildFile buildFile : detected) {
      byPath.putIfAbsent(buildFile.file().getPath(), buildFile);
    }
    for (String path : manualPaths) {
      if (byPath.containsKey(path)) continue;
      VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
      if (file == null || !file.isValid()) continue;
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
      if (type != null) {
        byPath.put(path, new HaxeBuildFile(file, type));
      }
    }
    filesStore.getHiddenPaths(containerId).forEach(byPath::remove);

    return byPath.values().stream()
      .sorted(Comparator.comparing(buildFile -> buildFile.file().getName(), String.CASE_INSENSITIVE_ORDER))
      .map(buildFile -> new FileEntry(buildFile, effectiveInfo(containerId, buildFile),
                                      manualPaths.contains(buildFile.file().getPath()),
                                      buildFileActions(containerId, buildFile)))
      .toList();
  }

  /**
   * The file's info for the tree. Lime-family files get their defines and libraries
   * from the LimeProjectParser evaluation for the selected target - conditionals
   * evaluated, toolchain defines included, and the library list is the FULL
   * resolved set (declared + transitive via include.xml/haxelib.json), so the
   * Libraries node shows everything the build actually loads. Falls back to the
   * raw parse until the background evaluation lands (or when only the legacy
   * lime-display path ran - its hxml carries no library identities).
   */
  @NotNull
  private HaxeBuildFileInfo effectiveInfo(@NotNull String containerId, @NotNull HaxeBuildFile buildFile) {
    HaxeBuildFileInfo raw = HaxeBuildFileInspector.inspect(buildFile);
    HaxeBuildFileType type = buildFile.type();
    if (!LimeProjects.isLimeFamily(type)) {
      return raw;
    }

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    HaxeBuildFileInfo display = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, onLimeEvaluationReady);
    if (display == null) {
      return raw;
    }
    List<HaxeBuildFileInfo.HaxeLibDependency> libraries =
      !display.libraries().isEmpty() ? display.libraries() : raw.libraries();
    // target + output come from the evaluation too: the actual haxe target and
    // artifact path of the SELECTED lime target (the raw xml declares neither)
    return new HaxeBuildFileInfo(display.target(), display.targetOutput(), display.defines(), libraries,
                                 display.classpaths());
  }

  /**
   * The build file's runnable actions: defaults for its type (using the file's
   * selected target and its container's environment SDK) plus its custom actions.
   * Everything runs in the build file's own directory.
   */
  @NotNull
  private List<ActionNode> buildFileActions(@NotNull String containerId, @NotNull HaxeBuildFile buildFile) {
    List<ActionNode> actions = new ArrayList<>();
    String ownerId = buildFile.file().getPath();
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    VirtualFile parent = buildFile.file().getParent();
    String workDirectory = parent != null ? parent.getPath() : project.getBasePath();

    addDefaultActions(actions, ownerId, buildFile, environmentSdk, workDirectory);
    for (HaxeCustomActionsStore.CustomAction custom : HaxeCustomActionsStore.getInstance(project).getActions(ownerId)) {
      List<String> command = ParametersListUtil.parse(custom.command());
      actions.add(new ActionNode(ownerId, custom.name(), command, workDirectory, custom.command(), true));
    }
    return actions;
  }

  private void addDefaultActions(@NotNull List<ActionNode> actions,
                                 @NotNull String ownerId,
                                 @NotNull HaxeBuildFile buildFile,
                                 @Nullable String environmentSdk,
                                 @Nullable String workDirectory) {
    VirtualFile file = buildFile.file();
    HaxeBuildFileType type = buildFile.type();
    switch (type) {
      case HXML -> {
        List<String> command = HxmlProjects.buildCommand(project, environmentSdk, file);
        String name = HaxeBundle.message("haxe.toolwindow.action.build");
        actions.add(new ActionNode(ownerId, name, command, workDirectory, "haxe " + file.getName(), false));
      }
      case OPENFL, LIME, HXP_PROJECT -> {
        String tool = LimeProjects.toolFor(type);
        String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
        for (String actionName : LimeProjects.DEFAULT_ACTIONS) {
          List<String> command = LimeProjects.actionCommand(project, environmentSdk, file, type, actionName);
          String presentable = tool + " " + actionName + " " + targetFlag;
          actions.add(new ActionNode(ownerId, actionName, command, workDirectory, presentable, false));
        }
      }
      case HXP_SCRIPT -> {
        // a plain hxp script builds itself - the hxp tool runs it, no lime target
        List<String> command = HaxeCompileCommands.hxpScriptCommand(project, environmentSdk, file);
        String presentable = "hxp " + file.getName();
        ActionNode actionNode = new ActionNode(ownerId, HaxeCompileCommands.HXP_SCRIPT_BUILD_ACTION, command,
                                      workDirectory, presentable, false);
        actions.add(actionNode);
      }
      case NMML -> { }
    }
  }

  /**
   * The container's Compile command row: the chosen file's type-derived command (or a
   * designated action of that file as override) plus extra arguments.
   */
  @NotNull
  private EnvCompileCommandNode compileCommandNode(@NotNull String containerId, @NotNull List<FileEntry> files) {
    List<String> candidatePaths = files.stream()
      .map(entry -> entry.buildFile().file().getPath())
      .toList();
    Map<String, List<String>> actionNamesByFile = files.stream()
      .collect(Collectors.toMap(entry -> entry.buildFile().file().getPath(),
                                entry -> entry.actions().stream().map(ActionNode::name).toList()));

    HaxeEnvironmentStore.CompileCommand stored = HaxeEnvironmentStore.getInstance(project).getCompileCommand(containerId);
    FileEntry chosen = stored == null ? null : files.stream()
      .filter(entry -> entry.buildFile().file().getPath().equals(stored.buildFilePath()))
      .findFirst()
      .orElse(null);
    if (chosen == null) {
      return new EnvCompileCommandNode(containerId, HaxeBundle.message("haxe.toolwindow.compile.command.not.set"),
                                       null, null, candidatePaths, actionNamesByFile, false);
    }

    HaxeBuildFile buildFile = chosen.buildFile();
    ActionNode overrideAction = stored.actionName() == null ? null : chosen.actions().stream()
      .filter(action -> action.name().equals(stored.actionName()))
      .findFirst()
      .orElse(null);

    List<String> baseCommand;
    String basePresentable;
    if (overrideAction != null) {
      baseCommand = overrideAction.command();
      basePresentable = overrideAction.presentableCommand();
    }
    else {
      baseCommand = defaultBuildCommand(containerId, buildFile);
      basePresentable = presentableBuildCommand(containerId, buildFile);
    }
    if (baseCommand == null || baseCommand.isEmpty()) {
      return new EnvCompileCommandNode(containerId,
                                       HaxeBundle.message("haxe.toolwindow.compile.command.unsupported", buildFile.file().getName()),
                                       null, null, candidatePaths, actionNamesByFile, false);
    }

    List<String> command = new ArrayList<>(baseCommand);
    command.addAll(ParametersListUtil.parse(stored.arguments()));
    VirtualFile parent = buildFile.file().getParent();
    String workDirectory = parent != null ? parent.getPath() : project.getBasePath();
    String display = StringUtil.trimTrailing(basePresentable + " " + stored.arguments());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    boolean connectEligible = HaxeCompileCommands.isConnectEligible(project, environmentSdk, command);
    return new EnvCompileCommandNode(containerId, display, command, workDirectory, candidatePaths, actionNamesByFile,
                                     connectEligible);
  }

  /** The type-derived build command, or null when the type has no build support (nmml). */
  @Nullable
  private List<String> defaultBuildCommand(@NotNull String containerId, @NotNull HaxeBuildFile buildFile) {
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    VirtualFile file = buildFile.file();
    return switch (buildFile.type()) {
      case HXML -> HxmlProjects.buildCommand(project, environmentSdk, file);
      case OPENFL, LIME, HXP_PROJECT ->
        LimeProjects.actionCommand(project, environmentSdk, file, buildFile.type(), LimeProjects.BUILD_ACTION);
      case HXP_SCRIPT -> HaxeCompileCommands.hxpScriptCommand(project, environmentSdk, file);
      case NMML -> null;
    };
  }

  @NotNull
  private String presentableBuildCommand(@NotNull String containerId, @NotNull HaxeBuildFile buildFile) {
    VirtualFile file = buildFile.file();
    return switch (buildFile.type()) {
      case HXML -> "haxe " + file.getName();
      case OPENFL, LIME, HXP_PROJECT -> {
        String tool = LimeProjects.toolFor(buildFile.type());
        String targetFlag = LimeProjects.selectedTargetFlag(project, buildFile.type(), file);
        yield tool + " build " + file.getName() + " " + targetFlag;
      }
      case HXP_SCRIPT -> "hxp " + file.getName();
      case NMML -> file.getName();
    };
  }

  @NotNull
  private EnvironmentData buildEnvironmentData(@NotNull String containerId, @NotNull Set<String> activeFileDefines) {
    HaxeEnvironmentStore environmentStore = HaxeEnvironmentStore.getInstance(project);

    String sdkName = environmentStore.getSdkName(containerId);
    String sdkDisplay;
    boolean sdkMissing = false;
    if (sdkName == null) {
      String projectSdk = HaxeBuildToolSettings.getInstance(project).getSdkName();
      String fallback = projectSdk != null ? projectSdk : HaxeBundle.message("haxe.toolwindow.node.environment.sdk.none");
      sdkDisplay = HaxeBundle.message("haxe.toolwindow.node.environment.sdk.default", fallback);
    }
    else {
      sdkDisplay = sdkName;
      sdkMissing = ProjectJdkTable.getInstance().findJdk(sdkName) == null;
    }

    // same store the Haxe Compiler settings page edits - the two stay in sync
    HaxeCompilerSettings compilerSettings = HaxeCompilerSettings.getInstance(project);
    HaxeLanguageLevel levelOverride = compilerSettings.getModuleLanguageLevelOverride(containerId);
    String levelDisplay = levelOverride != null
      ? levelOverride.getPresentableText()
      : HaxeBundle.message("haxe.toolwindow.node.environment.sdk.default",
                           compilerSettings.getDefaultLanguageLevel().getPresentableText());

    List<EnvDefineNode> defines = environmentStore.getDefines(containerId).stream()
      .map(define -> new EnvDefineNode(containerId, define.name(), define.value(), define.effect(),
                                       activeFileDefines.contains(define.name())))
      .toList();
    return new EnvironmentData(sdkDisplay, sdkMissing, levelDisplay, defines, activeFileDefines);
  }

  @NotNull
  private CompilationServerNode compilationServerNode(@NotNull String containerId, boolean connectEligible) {
    boolean projectEnabled = HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled();
    boolean moduleUses = HaxeEnvironmentStore.getInstance(project).isUsingCompilationServer(containerId);
    // per-module SDKs mean per-SDK server instances - this row reports the one
    // THIS container's compiles connect to, not whichever server happens to run
    String sdkName = HaxeToolPathResolver.effectiveSdkName(project, containerId);
    int port = HaxeCompilationServerManager.getInstance(project).getRunningPort(sdkName);
    boolean running = port > 0;

    String display;
    if (!projectEnabled) {
      display = HaxeBundle.message("haxe.toolwindow.server.disabled.project");
    }
    else if (!moduleUses) {
      display = HaxeBundle.message("haxe.toolwindow.server.off");
    }
    else if (!connectEligible) {
      display = HaxeBundle.message("haxe.toolwindow.server.not.applicable");
    }
    else {
      // port passed as text - MessageFormat would render the int with grouping separators
      display = running ? HaxeBundle.message("haxe.toolwindow.server.running", String.valueOf(port))
                        : HaxeBundle.message("haxe.toolwindow.server.on.idle");
    }
    return new CompilationServerNode(containerId, display, projectEnabled, moduleUses, running, connectEligible);
  }
}
