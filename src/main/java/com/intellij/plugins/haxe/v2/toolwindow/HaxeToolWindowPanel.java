package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType;
import com.intellij.plugins.haxe.haxelib.HaxelibInstalledIndex;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerListener;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
import com.intellij.plugins.haxe.v2.buildtools.HaxeDefineContextService;
import com.intellij.plugins.haxe.v2.buildtools.HaxeLimeDisplayService;
import com.intellij.plugins.haxe.v2.buildtools.HaxeModuleSdkApplier;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.toolwindow.actions.*;
import com.intellij.plugins.haxe.v2.toolwindow.tree.*;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.*;
import com.intellij.plugins.haxe.runner.HaxeRunConfigurationType;
import com.intellij.plugins.haxe.v2.runconfig.HaxeActionConfigurationFactory;
import com.intellij.plugins.haxe.v2.runconfig.HaxeActionRunConfiguration;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.toolwindow.ui.HaxeCompileCommandDialog;
import com.intellij.pom.Navigatable;
import com.intellij.util.PathUtil;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.tree.TreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Content panel of the Haxe tool window: a toolbar (sync, purge caches, execute
 * command, settings) above a tree of modules, their build files and each file's
 * target, defines and library dependencies.
 */
@CustomLog
public final class HaxeToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

  public static final String TOOLBAR_PLACE = "HaxeToolWindowToolbar";
  public static final String TREE_POPUP_PLACE = "HaxeToolWindowTreePopup";

  private static final List<String> LIME_DEFAULT_ACTIONS = List.of("test", "run", "build", "clean");

  /** Container id for the project root when no module owns the project base dir. */
  public static final String PROJECT_ROOT_CONTAINER = "/project-root";

  private final Project project;
  private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
  private final Tree tree = new Tree(treeModel);
  private boolean initialExpansionDone;

  private record FileEntry(HaxeBuildFile buildFile, HaxeBuildFileInfo info, boolean manual, List<ActionNode> actions) {
  }

  /** A build-file container: a module, or the project root for files outside every module. */
  private record ContainerEntry(String id, String displayName, boolean projectRoot,
                                List<FileEntry> files, @Nullable String activePath,
                                EnvironmentData environment,
                                EnvCompileCommandNode compileCommand,
                                CompilationServerNode server) {
  }

  /** The container's environment as shown in the tree, resolved during the scan read action. */
  private record EnvironmentData(String sdkDisplay, boolean sdkMissing, String languageLevelDisplay,
                                 List<EnvDefineNode> defines, Set<String> activeBuildFileDefines) {
  }

  public HaxeToolWindowPanel(@NotNull Project project) {
    super(true, true);
    this.project = project;

    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setCellRenderer(new HaxeToolWindowTreeRenderer());
    // per-row tooltips come from the renderer; a plain JTree never shows them unless registered
    ToolTipManager.sharedInstance().registerComponent(tree);
    tree.addMouseListener(new TreeClickHandler());
    PopupHandler.installPopupMenu(tree, createTreePopupGroup(), TREE_POPUP_PLACE);
    TreeSpeedSearch.installOn(tree, true, HaxeToolWindowPanel::speedSearchText);

    setToolbar(createToolbar());
    setContent(ScrollPaneFactory.createScrollPane(tree));

    project.getMessageBus().connect(this).subscribe(ModuleListener.TOPIC, new ModuleListener() {
      @Override
      public void modulesAdded(@NotNull Project project, @NotNull List<? extends Module> modules) {
        refreshTree();
      }

      @Override
      public void moduleRemoved(@NotNull Project project, @NotNull Module module) {
        refreshTree();
      }
    });
    project.getMessageBus().connect(this)
      .subscribe(HaxeCompilationServerListener.TOPIC, (HaxeCompilationServerListener)this::refreshTree);
    project.getMessageBus().connect(this)
      .subscribe(HaxeBuildConfigListener.TOPIC, (HaxeBuildConfigListener)this::refreshTree);

    refreshTree();
  }

  @NotNull
  private JComponent createToolbar() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new HaxeSyncProjectAction(this::refreshTree));
    group.add(new HaxePurgeCachesAction(this::refreshTree));
    group.addSeparator();
    group.add(new HaxeAddModuleAction());
    group.add(new HaxeRemoveModuleAction(this));
    group.addSeparator();
    group.add(new HaxeExecuteCommandAction());
    group.addSeparator();
    group.add(new HaxeSettingsActionGroup());

    group.addSeparator();
    CommonActionsManager commonActions = CommonActionsManager.getInstance();
    DefaultTreeExpander treeExpander = new DefaultTreeExpander(tree);
    group.add(commonActions.createExpandAllAction(treeExpander, tree));
    group.add(commonActions.createCollapseAllAction(treeExpander, tree));

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true);
    toolbar.setTargetComponent(this);
    return toolbar.getComponent();
  }

  @NotNull
  private DefaultActionGroup createTreePopupGroup() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new HaxeRunActionNodeAction(this));
    group.add(new HaxeRunProgramAction(this, false));
    group.add(new HaxeRunProgramAction(this, true));
    group.add(new HaxeSetAsCompileCommandAction(this));
    group.add(new HaxeAddCustomActionAction(this));
    group.add(new HaxeEditCustomActionAction(this));
    group.add(new HaxeRemoveCustomActionAction(this));
    group.add(new HaxeSetActiveBuildFileAction(this));
    group.add(new HaxeReloadBuildFileAction(this));
    group.add(new HaxeAddBuildFileAction(this));
    group.add(new HaxeRemoveBuildFileAction(this));
    group.add(new HaxeSelectTargetAction(this));
    group.add(new HaxeInstallLibraryAction(this));
    group.add(new HaxeConfigureEnvironmentAction(this));
    group.add(new HaxeConfigureCompileCommandAction(this));
    group.add(new HaxeRunCompileCommandAction(this));
    group.add(new HaxeSelectEnvironmentSdkAction(this));
    group.add(new HaxeAddDefineAction(this));
    group.add(new HaxeEditDefineAction(this));
    group.add(new HaxeRemoveDefineAction(this));
    return group;
  }

  /**
   * Rebuilds the tree: scan + parse under a non-blocking read action, then the
   * haxelib installed-library lookup (external process) outside any read action,
   * then the Swing model update on the EDT.
   */
  public void refreshTree() {
    // every configuration change funnels through here - keep the parse-context
    // defines in sync (cheap no-op when nothing changed)
    HaxeDefineContextService.getInstance(project).refreshAsync();
    ReadAction.nonBlocking(this::scanProject)
      .inSmartMode(project)
      .expireWith(this)
      .submit(AppExecutorUtil.getAppExecutorService())
      .onSuccess(scan -> AppExecutorUtil.getAppExecutorService().execute(() -> updateTree(scan)));
  }

  /** Never lets a haxelib failure prevent the tree from updating - install state just becomes unknown. */
  private void updateTree(@NotNull List<ContainerEntry> scan) {
    Map<String, String> installed = null;
    try {
      installed = fetchInstalledLibraryVersions();
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Exception e) {
      log.warn("haxelib lookup failed; library install state unknown", e);
    }
    DefaultMutableTreeNode root = buildTreeRoot(scan, installed);
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      applyTreeUpdate(root);
    });
  }

  /** A container before global active-file resolution. */
  private record RawContainer(String id, String displayName, boolean projectRoot, List<FileEntry> files) {
  }

  @NotNull
  private List<ContainerEntry> scanProject() {
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
      containers.add(new ContainerEntry(raw.id(), raw.displayName(), raw.projectRoot(), raw.files(), activePath,
                                        buildEnvironmentData(raw.id(), activeDefines),
                                        compileCommand,
                                        compilationServerNode(raw.id(), compileCommand.connectEligible())));
    }
    return containers;
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

  @NotNull
  private List<RawContainer> collectRawContainers() {
    List<RawContainer> rawContainers = new ArrayList<>();

    // The module whose content root is the project base dir IS the project - its build
    // files belong to the project node, and it gets no module row of its own.
    Module rootModule = findProjectRootModule();
    String rootContainerId = rootModule != null ? rootModule.getName() : PROJECT_ROOT_CONTAINER;
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
      rawContainers.add(new RawContainer(module.getName(), module.getName(), false,
                                         mergeAndInspect(module.getName(), HaxeBuildFileScanner.scan(module))));
    }
    return rawContainers;
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
    if (type != HaxeBuildFileType.OPENFL && type != HaxeBuildFileType.LIME && type != HaxeBuildFileType.HXP_PROJECT) {
      return raw;
    }

    String targetFlag = HaxeTargetOptions.targetFlagFor(
      type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(buildFile.file()));
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    HaxeBuildFileInfo display = HaxeLimeDisplayService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, this::refreshTree);
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
      actions.add(new ActionNode(ownerId, custom.name(), ParametersListUtil.parse(custom.command()),
                                 workDirectory, custom.command(), true));
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
        String haxeExe = HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk);
        actions.add(new ActionNode(ownerId, HaxeBundle.message("haxe.toolwindow.action.build"),
                                   List.of(haxeExe, file.getName()), workDirectory,
                                   "haxe " + file.getName(), false));
      }
      case OPENFL, LIME, HXP_PROJECT -> {
        String tool = type == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
        String haxelibExe = HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk);
        String targetFlag = HaxeTargetOptions.targetFlagFor(
          type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
        for (String actionName : LIME_DEFAULT_ACTIONS) {
          actions.add(new ActionNode(ownerId, actionName,
                                     List.of(haxelibExe, "run", tool, actionName, file.getName(), targetFlag),
                                     workDirectory,
                                     tool + " " + actionName + " " + targetFlag, false));
        }
      }
      case HXP_SCRIPT -> {
        // a plain hxp script builds itself - the hxp tool runs it, no lime target
        String haxelibExe = HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk);
        actions.add(new ActionNode(ownerId, HaxeCompileCommands.HXP_SCRIPT_BUILD_ACTION,
                                   List.of(haxelibExe, "run", "hxp", file.getName()), workDirectory,
                                   "hxp " + file.getName(), false));
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
      case HXML -> List.of(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk), file.getName());
      case OPENFL, LIME, HXP_PROJECT -> {
        String tool = buildFile.type() == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
        String targetFlag = HaxeTargetOptions.targetFlagFor(
          buildFile.type(), HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
        yield List.of(HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk),
                      "run", tool, "build", file.getName(), targetFlag);
      }
      case HXP_SCRIPT -> List.of(HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk),
                                 "run", "hxp", file.getName());
      case NMML -> null;
    };
  }

  @NotNull
  private String presentableBuildCommand(@NotNull String containerId, @NotNull HaxeBuildFile buildFile) {
    VirtualFile file = buildFile.file();
    return switch (buildFile.type()) {
      case HXML -> "haxe " + file.getName();
      case OPENFL, LIME, HXP_PROJECT -> {
        String tool = buildFile.type() == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
        String targetFlag = HaxeTargetOptions.targetFlagFor(
          buildFile.type(), HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
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

  @Nullable
  private Module findProjectRootModule() {
    VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    return baseDir == null ? null : ProjectFileIndex.getInstance(project).getModuleForFile(baseDir);
  }


  /**
   * Haxelib's selected version per installed library (lower-cased name, value may be null
   * when no version is selected), or null when haxelib is unavailable.
   */
  @Nullable
  private Map<String, String> fetchInstalledLibraryVersions() {
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

  /** Gradle-style structure: one project root node containing root-level build files and the modules. */
  @NotNull
  private DefaultMutableTreeNode buildTreeRoot(@NotNull List<ContainerEntry> scan, @Nullable Map<String, String> installedLibraries) {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    DefaultMutableTreeNode projectNode = new DefaultMutableTreeNode(new ProjectNode(project.getName()));
    root.add(projectNode);

    for (ContainerEntry container : scan) {
      if (container.projectRoot()) {
        projectNode.add(buildCompilationGroupNode(container));
        projectNode.add(buildEnvironmentNode(container));
        projectNode.add(buildBuildGroupNode(container, installedLibraries));
      }
      else {
        DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(new ModuleNode(container.displayName()));
        moduleNode.add(buildCompilationGroupNode(container));
        moduleNode.add(buildEnvironmentNode(container));
        moduleNode.add(buildBuildGroupNode(container, installedLibraries));
        projectNode.add(moduleNode);
      }
    }
    return root;
  }

  @NotNull
  private static DefaultMutableTreeNode buildActionsGroupNode(@NotNull FileEntry entry) {
    String launchKind = HaxeProgramLaunches.launchKind(entry.info(), entry.buildFile().type());
    int count = entry.actions().size() + (launchKind != null ? 1 : 0);
    DefaultMutableTreeNode actionsNode = new DefaultMutableTreeNode(
      new ActionsGroupNode(entry.buildFile().file().getPath(), count));
    for (ActionNode action : entry.actions()) {
      actionsNode.add(new DefaultMutableTreeNode(action));
    }
    if (launchKind != null) {
      actionsNode.add(new DefaultMutableTreeNode(
        new ProgramNode(entry.buildFile(), launchKind, entry.info().target(), entry.info().targetOutput())));
    }
    return actionsNode;
  }

  @NotNull
  private static DefaultMutableTreeNode buildCompilationGroupNode(@NotNull ContainerEntry container) {
    DefaultMutableTreeNode compilationNode = new DefaultMutableTreeNode(new CompilationGroupNode(container.id()));
    compilationNode.add(new DefaultMutableTreeNode(container.compileCommand()));
    compilationNode.add(new DefaultMutableTreeNode(container.server()));
    return compilationNode;
  }

  @NotNull
  private DefaultMutableTreeNode buildBuildGroupNode(@NotNull ContainerEntry container,
                                                     @Nullable Map<String, String> installedLibraries) {
    DefaultMutableTreeNode buildNode =
      new DefaultMutableTreeNode(new BuildGroupNode(container.id(), container.files().size()));
    addContainerFiles(buildNode, container, installedLibraries);
    return buildNode;
  }

  @NotNull
  private static DefaultMutableTreeNode buildEnvironmentNode(@NotNull ContainerEntry container) {
    EnvironmentData environment = container.environment();
    DefaultMutableTreeNode environmentNode = new DefaultMutableTreeNode(
      new EnvironmentNode(container.id(), container.displayName(), environment.activeBuildFileDefines()));
    environmentNode.add(new DefaultMutableTreeNode(
      new EnvSdkNode(container.id(), environment.sdkDisplay(), environment.sdkMissing())));
    environmentNode.add(new DefaultMutableTreeNode(
      new EnvLanguageLevelNode(container.id(), environment.languageLevelDisplay())));

    DefaultMutableTreeNode definesNode =
      new DefaultMutableTreeNode(new EnvDefinesNode(container.id(), environment.defines().size()));
    for (EnvDefineNode define : environment.defines()) {
      definesNode.add(new DefaultMutableTreeNode(define));
    }
    environmentNode.add(definesNode);
    return environmentNode;
  }

  private void addContainerFiles(@NotNull DefaultMutableTreeNode parentNode,
                                 @NotNull ContainerEntry container,
                                 @Nullable Map<String, String> installedLibraries) {
    for (FileEntry fileEntry : container.files()) {
      boolean active = fileEntry.buildFile().file().getPath().equals(container.activePath());
      parentNode.add(buildFileNode(fileEntry, container.id(), active, fileEntry.manual(), installedLibraries));
    }
  }

  @NotNull
  private DefaultMutableTreeNode buildFileNode(@NotNull FileEntry entry,
                                               @NotNull String containerId,
                                               boolean active,
                                               boolean manual,
                                               @Nullable Map<String, String> installedLibraries) {
    HaxeBuildFile buildFile = entry.buildFile();
    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(new BuildFileRow(buildFile, containerId, active, manual));
    // a plain hxp script decides its own targets in code - no target row
    if (buildFile.type() != HaxeBuildFileType.HXP_SCRIPT) {
      fileNode.add(new DefaultMutableTreeNode(buildTargetNode(entry)));
    }


    HaxeBuildFileInfo info = entry.info();
    DefaultMutableTreeNode definesNode =
      new DefaultMutableTreeNode(new GroupNode(GroupKind.DEFINES, info.defines().size()));
    for (HaxeBuildFileInfo.HaxeDefine define : info.defines()) {
      definesNode.add(new DefaultMutableTreeNode(new DefineNode(buildFile, define.name(), define.value())));
    }
    fileNode.add(definesNode);

    DefaultMutableTreeNode librariesNode =
      new DefaultMutableTreeNode(new GroupNode(GroupKind.LIBRARIES, info.libraries().size()));
    for (HaxeBuildFileInfo.HaxeLibDependency library : info.libraries()) {
      String key = library.name().toLowerCase(Locale.ROOT);
      boolean installed = installedLibraries == null || installedLibraries.containsKey(key);
      String resolvedVersion = installedLibraries != null ? installedLibraries.get(key) : null;
      librariesNode.add(new DefaultMutableTreeNode(
        new LibraryNode(buildFile, library.name(), library.version(), resolvedVersion, installed)));
    }
    fileNode.add(librariesNode);
    fileNode.add(buildActionsGroupNode(entry));
    return fileNode;
  }

  @NotNull
  private TargetNode buildTargetNode(@NotNull FileEntry entry) {
    HaxeBuildFile buildFile = entry.buildFile();
    if (!HaxeTargetOptions.isTargetSelectable(buildFile.type())) {
      HaxeTarget target = entry.info().target();
      String display = target != null ? target.toString()
                                      : HaxeBundle.message("haxe.toolwindow.node.target.unspecified");
      return new TargetNode(buildFile, display, false);
    }
    String selectedId = HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(buildFile.file());
    return new TargetNode(buildFile, HaxeTargetOptions.displayNameFor(buildFile.type(), selectedId), true);
  }

  /** Shows the target dropdown for a selectable target row, anchored at the given point. */
  public void showTargetPopup(@NotNull TargetNode targetNode, @NotNull RelativePoint point) {
    List<HaxeTargetOptions.TargetChoice> choices = HaxeTargetOptions.choicesFor(targetNode.buildFile().type());
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(choices)
      .setTitle(HaxeBundle.message("haxe.toolwindow.select.target.title"))
      .setRenderer(BuilderKt.textListCellRenderer("", HaxeTargetOptions.TargetChoice::displayName))
      .setItemChosenCallback(choice -> {
        HaxeTargetSelectionStore.getInstance(project).setSelectedTargetId(targetNode.buildFile().file(), choice.id());
        refreshTree();
      })
      .createPopup()
      .show(point);
  }

  /** Shows the environment SDK dropdown, anchored at the given point. */
  public void showEnvironmentSdkPopup(@NotNull EnvSdkNode sdkNode, @NotNull RelativePoint point) {
    List<SdkChoice> choices = new ArrayList<>();
    choices.add(new SdkChoice(null, HaxeBundle.message("haxe.toolwindow.environment.sdk.default.choice")));
    for (Sdk sdk : ProjectJdkTable.getInstance().getSdksOfType(HaxeSdkType.getInstance())) {
      choices.add(new SdkChoice(sdk.getName(), sdk.getName()));
    }
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(choices)
      .setTitle(HaxeBundle.message("haxe.toolwindow.select.sdk.title"))
      .setRenderer(BuilderKt.textListCellRenderer("", SdkChoice::display))
      .setItemChosenCallback(choice -> {
        HaxeEnvironmentStore.getInstance(project).setSdkName(sdkNode.containerId(), choice.name());
        HaxeModuleSdkApplier.getInstance(project).applyAsync(sdkNode.containerId(), choice.name());
        refreshTree();
      })
      .createPopup()
      .show(point);
  }

  private record SdkChoice(@Nullable String name, @NotNull String display) {
  }

  /** Level choices mirror the Haxe Compiler settings page: an explicit level, or null = project default. */
  public void showLanguageLevelPopup(@NotNull EnvLanguageLevelNode levelNode, @NotNull RelativePoint point) {
    HaxeCompilerSettings compilerSettings = HaxeCompilerSettings.getInstance(project);
    List<LevelChoice> choices = new ArrayList<>();
    choices.add(new LevelChoice(null, HaxeBundle.message(
      "haxe.toolwindow.node.environment.sdk.default", compilerSettings.getDefaultLanguageLevel().getPresentableText())));
    for (HaxeLanguageLevel level : HaxeLanguageLevel.values()) {
      choices.add(new LevelChoice(level, level.getPresentableText()));
    }
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(choices)
      .setTitle(HaxeBundle.message("haxe.toolwindow.select.language.level.title"))
      .setRenderer(BuilderKt.textListCellRenderer("", LevelChoice::display))
      .setItemChosenCallback(choice -> {
        compilerSettings.setModuleLanguageLevelOverride(levelNode.containerId(), choice.level());
        refreshTree();
      })
      .createPopup()
      .show(point);
  }

  private record LevelChoice(@Nullable HaxeLanguageLevel level, @NotNull String display) {
  }

  /** User object of the tree's selected node, or null. */
  @Nullable
  public Object getSelectedUserObject() {
    TreePath path = tree.getSelectionPath();
    if (path == null) return null;
    return path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
  }

  /** The text speed search matches against - the row's primary label as rendered. */
  @NotNull
  private static String speedSearchText(@NotNull TreePath path) {
    if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return "";
    return switch (node.getUserObject()) {
      case ProjectNode projectNode -> projectNode.name();
      case ModuleNode moduleNode -> moduleNode.name();
      case BuildFileRow row -> row.buildFile().file().getName();
      case TargetNode targetNode -> targetNode.displayName();
      case GroupNode groupNode -> groupNode.kind() == GroupKind.LIBRARIES
                                  ? HaxeBundle.message("haxe.toolwindow.node.libraries")
                                  : HaxeBundle.message("haxe.toolwindow.node.defines");
      case DefineNode defineNode -> defineNode.name();
      case LibraryNode libraryNode -> libraryNode.name();
      case BuildGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.node.build");
      case ActionsGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.node.actions");
      case ActionNode actionNode -> actionNode.name();
      case ProgramNode ignored -> HaxeBundle.message("haxe.toolwindow.node.program");
      case EnvironmentNode ignored -> HaxeBundle.message("haxe.toolwindow.node.environment");
      case CompilationGroupNode ignored -> HaxeBundle.message("haxe.toolwindow.node.compilation");
      case CompilationServerNode serverNode -> serverNode.display();
      case EnvCompileCommandNode buildCommand -> buildCommand.display();
      case EnvSdkNode sdkNode -> sdkNode.displayName();
      case EnvLanguageLevelNode levelNode -> levelNode.displayName();
      case EnvDefinesNode ignored -> HaxeBundle.message("haxe.toolwindow.node.environment.defines");
      case EnvDefineNode defineNode -> defineNode.name();
      case null, default -> "";
    };
  }

  /** Supplies the selection's jump-to-source target so F4 / Jump to Source works on tree rows. */
  @Override
  public void uiDataSnapshot(@NotNull DataSink sink) {
    super.uiDataSnapshot(sink);
    Navigatable navigatable = HaxeToolWindowNavigation.forSelection(project, getSelectedUserObject());
    if (navigatable != null) {
      sink.set(CommonDataKeys.NAVIGATABLE, navigatable);
    }
  }

  /** Opens the compile command configuration dialog and refreshes on OK. */
  public void configureCompileCommand(@NotNull EnvCompileCommandNode compileCommand) {
    HaxeCompileCommandDialog dialog = new HaxeCompileCommandDialog(project, compileCommand.containerId(),
                                                                   compileCommand.candidateFilePaths(),
                                                                   compileCommand.actionNamesByFile());
    if (dialog.showAndGet()) {
      refreshTree();
    }
  }

  /** The build file row enclosing the selection (an action or child row), or null. */
  @Nullable
  public BuildFileRow getSelectedBuildFileRowAncestor() {
    TreePath path = tree.getSelectionPath();
    while (path != null) {
      if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof BuildFileRow row) {
        return row;
      }
      path = path.getParentPath();
    }
    return null;
  }

  /**
   * Runs the container's configured compile command (no-op while unconfigured),
   * routed through the compilation server when enabled and applicable.
   */
  public void runCompileCommand(@NotNull EnvCompileCommandNode compileCommand) {
    if (compileCommand.command() == null) return;
    // connect injection may spawn the compilation server - keep process creation off the EDT
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      List<String> command = HaxeCompileCommands.connectIfEnabled(
        project, compileCommand.containerId(), compileCommand.connectEligible(), compileCommand.command());
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!project.isDisposed()) {
          HaxeCommandRunner.run(project, compileCommand.display(), command, compileCommand.workDirectory());
        }
      });
    });
  }

  public void runAction(@NotNull ActionNode actionNode) {
    executeAction(actionNode, DefaultRunExecutor.getRunExecutorInstance());
  }

  /**
   * Executes an action row through a run configuration (created on first use,
   * reused after), so it lands in the run configuration dropdown and can be rerun
   * or debugged from the main UI - the Gradle tool window pattern.
   */
  private void executeAction(@NotNull ActionNode actionNode, @NotNull Executor executor) {
    if (actionNode.command().isEmpty()) return;

    RunManager runManager = RunManager.getInstance(project);
    RunnerAndConfigurationSettings settings = runManager.getAllSettings().stream()
      .filter(candidate -> candidate.getConfiguration() instanceof HaxeActionRunConfiguration configuration
                           && configuration.getBuildFilePath().equals(actionNode.ownerId())
                           && configuration.getActionName().equals(actionNode.name()))
      .findFirst()
      .orElse(null);

    if (settings == null) {
      String name = PathUtil.getFileName(actionNode.ownerId()) + " " + actionNode.name();
      settings = runManager.createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(HaxeActionConfigurationFactory.class));
      HaxeActionRunConfiguration configuration = (HaxeActionRunConfiguration)settings.getConfiguration();
      configuration.setBuildFilePath(actionNode.ownerId());
      configuration.setActionName(actionNode.name());
      runManager.addConfiguration(settings);
    }
    runManager.setSelectedConfiguration(settings);
    ProgramRunnerUtil.executeConfiguration(settings, executor);
  }

  /**
   * The "compile &amp; run" row: launches the program a build file produces,
   * through the target's visible run configuration (HashLink, Browser, …) whose
   * before-launch step compiles the file (with the target's debug additions
   * under the Debug executor).
   */
  public void executeProgram(@NotNull ProgramNode programNode, boolean debug) {
    VirtualFile file = programNode.buildFile().file();
    // the file index needs a read action - the EDT has no implicit read access
    Module module = ReadAction.compute(() -> ProjectFileIndex.getInstance(project).getModuleForFile(file));
    if (module == null) {
      notifyUser(HaxeBundle.message("haxe.toolwindow.program.no.module", file.getName()));
      return;
    }

    RunnerAndConfigurationSettings settings = HaxeProgramLaunches.findOrCreate(
      project, module, programNode.buildFile(), programNode.target(), programNode.targetOutput());
    if (settings == null) {
      notifyUser(HaxeBundle.message("haxe.toolwindow.program.unsupported", file.getName()));
      return;
    }
    RunManager.getInstance(project).setSelectedConfiguration(settings);
    Executor executor = debug ? DefaultDebugExecutor.getDebugExecutorInstance()
                              : DefaultRunExecutor.getRunExecutorInstance();
    ProgramRunnerUtil.executeConfiguration(settings, executor);
  }

  private void notifyUser(@NotNull String message) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("haxe.command")
      .createNotification(message, NotificationType.WARNING)
      .notify(project);
  }

  /** Toggles the container's server participation; while disabled project-wide, opens the settings page instead. */
  public void toggleCompilationServer(@NotNull CompilationServerNode serverNode) {
    if (!serverNode.projectEnabled()) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, HaxeBuildToolsConfigurable.class);
    }
    else {
      HaxeEnvironmentStore.getInstance(project).setUsingCompilationServer(serverNode.containerId(), !serverNode.moduleUses());
    }
    refreshTree();
  }

  /** The Environment row of the selection, walking up from any of its child rows. */
  @Nullable
  public EnvironmentNode getSelectedEnvironmentNode() {
    TreePath path = tree.getSelectionPath();
    while (path != null) {
      if (path.getLastPathComponent() instanceof DefaultMutableTreeNode node
          && node.getUserObject() instanceof EnvironmentNode environmentNode) {
        return environmentNode;
      }
      path = path.getParentPath();
    }
    return null;
  }

  /** Anchor point for popups on the selected row, falling back to the tree's corner. */
  @NotNull
  public RelativePoint getSelectionPopupPoint() {
    TreePath path = tree.getSelectionPath();
    var bounds = path != null ? tree.getPathBounds(path) : null;
    if (bounds == null) return new RelativePoint(tree, new Point(0, 0));
    return new RelativePoint(tree, new Point(bounds.x, bounds.y + bounds.height));
  }

  /**
   * Replaces the model while preserving the user's expansion and selection state.
   * Rows are matched by stable identity (file path, module name), so volatile parts
   * of a row - counts, the active marker, versions - do not reset the view.
   */
  private void applyTreeUpdate(@NotNull DefaultMutableTreeNode newRoot) {
    Set<String> expandedKeys = collectExpandedKeys();
    String selectedKey = chainKey(tree.getSelectionPath());

    treeModel.setRoot(newRoot);
    if (!initialExpansionDone) {
      initialExpansionDone = true;
      // project node, modules, Environment/Build groups and build files visible; deeper rows stay collapsed
      TreeUtil.expand(tree, 4);
      return;
    }
    forEachNode(newRoot, node -> {
      TreePath path = new TreePath(node.getPath());
      String key = chainKey(path);
      if (expandedKeys.contains(key)) {
        tree.expandPath(path);
      }
      if (key != null && key.equals(selectedKey)) {
        tree.setSelectionPath(path);
      }
    });
  }

  @NotNull
  private Set<String> collectExpandedKeys() {
    Set<String> keys = new HashSet<>();
    if (treeModel.getRoot() == null) return keys;
    Enumeration<TreePath> expanded = tree.getExpandedDescendants(new TreePath(treeModel.getRoot()));
    while (expanded != null && expanded.hasMoreElements()) {
      String key = chainKey(expanded.nextElement());
      if (key != null) {
        keys.add(key);
      }
    }
    return keys;
  }

  private static void forEachNode(@NotNull DefaultMutableTreeNode node, @NotNull Consumer<DefaultMutableTreeNode> visitor) {
    visitor.accept(node);
    for (int i = 0; i < node.getChildCount(); i++) {
      forEachNode((DefaultMutableTreeNode)node.getChildAt(i), visitor);
    }
  }

  /** Stable identity of a row's ancestry, independent of volatile row content. */
  @Nullable
  private static String chainKey(@Nullable TreePath path) {
    if (path == null) return null;
    StringBuilder key = new StringBuilder();
    for (Object component : path.getPath()) {
      if (!(component instanceof DefaultMutableTreeNode node) || node.getUserObject() == null) continue;
      key.append('|').append(nodeKey(node.getUserObject()));
    }
    return key.toString();
  }

  @NotNull
  private static String nodeKey(@NotNull Object userObject) {
    return switch (userObject) {
      case ProjectNode ignored -> "project";
      case ModuleNode moduleNode -> "module:" + moduleNode.name();
      case BuildFileRow row -> "file:" + row.buildFile().file().getPath();
      case TargetNode targetNode -> "target:" + targetNode.buildFile().file().getPath();
      case GroupNode groupNode -> "group:" + groupNode.kind();
      case DefineNode defineNode -> "define:" + defineNode.name();
      case LibraryNode libraryNode -> "lib:" + libraryNode.name();
      case EnvironmentNode ignored -> "env";
      case CompilationGroupNode ignored -> "compilation";
      case CompilationServerNode ignored -> "server";
      case EnvCompileCommandNode ignored -> "envcompile";
      case BuildGroupNode ignored -> "build";
      case ActionsGroupNode ignored -> "actions";
      case ActionNode actionNode -> "action:" + actionNode.name();
      case ProgramNode ignored -> "program";
      case EnvSdkNode ignored -> "envsdk";
      case EnvLanguageLevelNode ignored -> "envlevel";
      case EnvDefinesNode ignored -> "envdefines";
      case EnvDefineNode defineNode -> "envdef:" + defineNode.name();
      default -> String.valueOf(userObject);
    };
  }

  private final class TreeClickHandler extends MouseAdapter {
    @Override
    public void mouseClicked(MouseEvent e) {
      if (!SwingUtilities.isLeftMouseButton(e)) return;
      TreePath path = tree.getPathForLocation(e.getX(), e.getY());
      if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return;

      if (e.getClickCount() == 1 && node.getUserObject() instanceof TargetNode targetNode && targetNode.selectable()) {
        showTargetPopup(targetNode, new RelativePoint(e.getComponent(), e.getPoint()));
      }
      else if (e.getClickCount() == 1 && node.getUserObject() instanceof EnvSdkNode sdkNode) {
        showEnvironmentSdkPopup(sdkNode, new RelativePoint(e.getComponent(), e.getPoint()));
      }
      else if (e.getClickCount() == 1 && node.getUserObject() instanceof EnvLanguageLevelNode levelNode) {
        showLanguageLevelPopup(levelNode, new RelativePoint(e.getComponent(), e.getPoint()));
      }
      else if (e.getClickCount() == 1 && node.getUserObject() instanceof EnvCompileCommandNode buildCommand) {
        configureCompileCommand(buildCommand);
      }
      else if (e.getClickCount() == 1 && node.getUserObject() instanceof CompilationServerNode serverNode) {
        toggleCompilationServer(serverNode);
      }
      else if (e.getClickCount() == 2 && node.getUserObject() instanceof BuildFileRow row && row.buildFile().file().isValid()) {
        new OpenFileDescriptor(project, row.buildFile().file()).navigate(true);
      }
      else if (e.getClickCount() == 2 && node.getUserObject() instanceof ActionNode actionNode) {
        runAction(actionNode);
      }
      else if (e.getClickCount() == 2 && node.getUserObject() instanceof ProgramNode programNode) {
        executeProgram(programNode, false);
      }
    }
  }

  @Override
  public void dispose() {
    // message bus connection is tied to this Disposable; nothing else to release
  }
}
