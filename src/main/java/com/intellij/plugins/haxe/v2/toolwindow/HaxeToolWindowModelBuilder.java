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
import com.intellij.plugins.haxe.v2.testing.HaxeTestFrameworks;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.*;
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
  // fired when a background lime/nme evaluation lands, so the owner can re-scan
  private final Runnable onEvaluationReady;

  HaxeToolWindowModelBuilder(@NotNull Project project, @NotNull Runnable onEvaluationReady) {
    this.project = project;
    this.onEvaluationReady = onEvaluationReady;
  }

  /** {@code sectionIds}/{@code sectionLabels} list a multi-section hxml's {@code --next} compilations (empty otherwise); {@code selectedSection} indexes into them. */
  record FileEntry(HaxeBuildFile buildFile, HaxeBuildFileInfo info, boolean manual, List<ActionNode> actions,
                   List<String> sectionIds, List<String> sectionLabels, int selectedSection) {
  }

  /** A build-file container: a module, or the project root for files outside every module. */
  record ContainerEntry(String id, String displayName, boolean projectRoot,
                        List<FileEntry> files, @Nullable String activePath,
                        List<String> testsPaths,
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
      List<String> testsPaths = resolveTestsPaths(raw);
      ContainerEntry container = new ContainerEntry(raw.id(), raw.displayName(), raw.projectRoot(), raw.files(),
                                                    activePath, testsPaths, environment, compileCommand, server);
      containers.add(container);
    }
    return containers;
  }

  /**
   * The container's tests build files: the marked ones, or the store's
   * convention-based suggestions - in both cases only builds DECLARING a
   * known test framework lib, so a plain application build (even a marked
   * one) never presents a test run it cannot deliver.
   */
  @NotNull
  private List<String> resolveTestsPaths(@NotNull RawContainer raw) {
    List<String> candidatePaths = raw.files().stream()
      .filter(entry -> HaxeTestFrameworks.detectedFramework(entry.info().libraries()) != null)
      .map(entry -> entry.buildFile().file().getPath())
      .toList();
    return HaxeTestsBuildFileStore.getInstance(project).resolveTestsFiles(raw.id(), candidatePaths);
  }

  /** One installed haxelib: the selected version (null when none is set) and every installed version. */
  record InstalledLibrary(@Nullable String selectedVersion, @NotNull Set<String> versions) {
  }

  /**
   * Haxelib's install state per library (lower-cased name), or null when haxelib
   * is unavailable. Runs an external process - call outside read actions.
   */
  @Nullable
  static Map<String, InstalledLibrary> fetchInstalledLibraryVersions(@NotNull Project project) {
    Sdk sdk = HaxeToolPathResolver.findConfiguredSdk(project);
    VirtualFile workDir = ProjectUtil.guessProjectDir(project);
    if (sdk == null || workDir == null) return null;

    HaxelibInstalledIndex index = HaxelibInstalledIndex.fetchFromHaxelib(sdk, workDir);
    Map<String, InstalledLibrary> byName = new HashMap<>();
    for (String name : index.getInstalledLibraries()) {
      InstalledLibrary library = new InstalledLibrary(index.getSelectedVersion(name), index.getInstalledVersions(name));
      byName.put(name.toLowerCase(Locale.ROOT), library);
    }
    return byName;
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
    Set<String> manualPaths = new LinkedHashSet<>(HaxeBuildFilesStore.getInstance(project).getAddedPaths(containerId));

    return HaxeKnownBuildFiles.mergeWithStore(project, containerId, detected).stream()
      .sorted(Comparator.comparing(buildFile -> buildFile.file().getName(), String.CASE_INSENSITIVE_ORDER))
      .map(buildFile -> fileEntry(containerId, buildFile, manualPaths))
      .toList();
  }

  @NotNull
  private FileEntry fileEntry(@NotNull String containerId, @NotNull HaxeBuildFile buildFile,
                              @NotNull Set<String> manualPaths) {
    List<String> sectionIds = sectionIds(buildFile);
    List<String> sectionLabels = sectionLabels(sectionIds);
    int selectedSection = sectionIds.isEmpty() ? 0
      : HaxeSectionSelectionStore.getInstance(project).getSelectedSection(buildFile.file(), sectionIds);
    return new FileEntry(buildFile, effectiveInfo(containerId, buildFile),
                         manualPaths.contains(buildFile.file().getPath()),
                         buildFileActions(containerId, buildFile),
                         sectionIds, sectionLabels, selectedSection);
  }

  /** One identity per {@code --next} section of a multi-section hxml; empty for everything else. */
  @NotNull
  private List<String> sectionIds(@NotNull HaxeBuildFile buildFile) {
    if (buildFile.type() != HaxeBuildFileType.HXML) return List.of();
    List<String> sections = HaxeBuildFileInspector.sectionContents(project, buildFile.file());
    if (sections.size() < 2) return List.of();
    return HxmlFileParser.sectionIds(buildFile.file().getName(), sections);
  }

  /**
   * Section labels are FILENAMES, never targets: the file the section's
   * content came from, the build file's own name for inline sections, and a
   * counter when one file chains several builds internally
   * ("1: compile-cs.hxml", "2: compile-cs.hxml (2)").
   */
  @NotNull
  private static List<String> sectionLabels(@NotNull List<String> sectionIds) {
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < sectionIds.size(); i++) {
      String id = sectionIds.get(i);
      // the identity's "#n" occurrence suffix reads better as " (n)"
      int hash = id.lastIndexOf('#');
      String name = hash < 0 ? id : id.substring(0, hash) + " (" + id.substring(hash + 1) + ")";
      labels.add((i + 1) + ": " + name);
    }
    return labels;
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
    HaxeBuildFileInfo raw = HaxeBuildSections.inspectSelected(project, buildFile);
    HaxeBuildFileType type = buildFile.type();
    if (type == HaxeBuildFileType.NMML) {
      return nmeEffectiveInfo(containerId, buildFile, raw);
    }
    if (!LimeProjects.isLimeFamily(type)) {
      return raw;
    }

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    HaxeBuildFileInfo display = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, onEvaluationReady);
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
   * NMML info: target + artifact derive statically from the selected target
   * (the nme tool's output layout is fixed), while defines, classpaths and the
   * FULL library set (include.nmml transitives, asset handlers) come from the
   * background `nme prepare` evaluation - the raw xml parse serves until it
   * lands. The prepared hxml flattens libs into classpaths, so a run whose
   * derived library list is empty keeps the declared one.
   */
  @NotNull
  private HaxeBuildFileInfo nmeEffectiveInfo(@NotNull String containerId,
                                             @NotNull HaxeBuildFile buildFile,
                                             @NotNull HaxeBuildFileInfo raw) {
    VirtualFile file = buildFile.file();
    HaxeBuildFileInfo withArtifact = NmeProjects.withTargetArtifact(project, file, raw);

    String targetFlag = NmeProjects.selectedTargetFlag(project, file);
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    HaxeNmeProjectInfoService.Evaluation evaluation = HaxeNmeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, onEvaluationReady);
    if (evaluation == null) {
      return withArtifact;
    }
    HaxeBuildFileInfo prepared = evaluation.info();
    List<HaxeBuildFileInfo.HaxeLibDependency> libraries =
      !prepared.libraries().isEmpty() ? prepared.libraries() : raw.libraries();
    return new HaxeBuildFileInfo(withArtifact.target(), withArtifact.targetOutput(), prepared.defines(), libraries,
                                 prepared.classpaths());
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
      // the row shows the expanded command, so a ${target} action reads like the default ones
      String expanded = HaxeCustomCommands.expandVariables(project, buildFile.file(), buildFile.type(), custom.command());
      List<String> command = HaxeCustomCommands.parse(expanded);
      actions.add(new ActionNode(ownerId, custom.name(), command, workDirectory, expanded, true));
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
        String targetFlags = String.join(" ", LimeProjects.selectedTargetFlags(project, type, file));
        for (String actionName : LimeProjects.DEFAULT_ACTIONS) {
          List<String> command = LimeProjects.actionCommand(project, environmentSdk, file, type, actionName);
          String presentable = tool + " " + actionName + " " + targetFlags;
          actions.add(new ActionNode(ownerId, actionName, command, workDirectory, presentable, false));
        }
      }
      case HXP_SCRIPT -> {
        // a plain hxp script builds itself - the hxp tool runs it, no lime target
        List<String> command = HxpScriptProjects.buildCommand(project, environmentSdk, file);

        String presentable = "hxp " + file.getName();
        ActionNode actionNode = new ActionNode(ownerId, HxpScriptProjects.BUILD_ACTION, command,
                                      workDirectory, presentable, false);
        actions.add(actionNode);
      }
      case NMML -> {
        String targetFlags = String.join(" ", NmeProjects.selectedTargetFlags(project, file));
        for (String actionName : NmeProjects.DEFAULT_ACTIONS) {
          List<String> command = NmeProjects.actionCommand(project, environmentSdk, file, actionName);
          String presentable = "nme " + actionName + " " + targetFlags;
          actions.add(new ActionNode(ownerId, actionName, command, workDirectory, presentable, false));
        }
      }
    }
  }

  /**
   * The container's Compile command row: the chosen file's default build action (or a
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
    ActionNode overrideAction = stored.actionName() == null ? null
                                                            : findAction(chosen.actions(), stored.actionName());
    ActionNode baseAction = overrideAction != null
      ? overrideAction
      : findAction(chosen.actions(), defaultBuildActionName(buildFile.type()));
    if (baseAction == null || baseAction.command().isEmpty()) {
      return new EnvCompileCommandNode(containerId,
                                       HaxeBundle.message("haxe.toolwindow.compile.command.unsupported", buildFile.file().getName()),
                                       null, null, candidatePaths, actionNamesByFile, false);
    }

    List<String> command = new ArrayList<>(baseAction.command());
    command.addAll(ParametersListUtil.parse(stored.arguments()));
    String display = StringUtil.trimTrailing(baseAction.presentableCommand() + " " + stored.arguments());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    boolean connectEligible = HaxeCompileCommands.isConnectEligible(project, environmentSdk, command)
                              && !HaxeCompileCommands.producesSwf(project, buildFile.file());
    return new EnvCompileCommandNode(containerId, display, command, baseAction.workDirectory(), candidatePaths,
                                     actionNamesByFile, connectEligible);
  }

  @Nullable
  private static ActionNode findAction(@NotNull List<ActionNode> actions, @NotNull String name) {
    return actions.stream()
      .filter(action -> action.name().equals(name))
      .findFirst()
      .orElse(null);
  }

  /** The name {@link #addDefaultActions} gives the type's build action. */
  @NotNull
  private static String defaultBuildActionName(@NotNull HaxeBuildFileType type) {
    return switch (type) {
      case HXML -> HaxeBundle.message("haxe.toolwindow.action.build");
      case OPENFL, LIME, HXP_PROJECT -> LimeProjects.BUILD_ACTION;
      case HXP_SCRIPT -> HxpScriptProjects.BUILD_ACTION;
      case NMML -> NmeProjects.BUILD_ACTION;
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
                           compilerSettings.getDefaultLanguageLevel(containerId).getPresentableText());

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
    if (!HaxeProjectTrust.isTrusted(project)) {
      display = HaxeBundle.message("haxe.trust.toolwindow.hint");
    }
    else if (!projectEnabled) {
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
    String contextFailure = HaxeContextHealth.getInstance(project).lastFailure(containerId);
    return new CompilationServerNode(containerId, display, projectEnabled, moduleUses, running, connectEligible,
                                     contextFailure);
  }
}
