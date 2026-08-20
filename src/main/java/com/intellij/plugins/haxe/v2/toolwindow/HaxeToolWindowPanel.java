package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.execution.Executor;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType;
import com.intellij.plugins.haxe.v2.testing.HaxeTestFrameworks;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCommandNotifications;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeCompilationServerListener;
import com.intellij.plugins.haxe.v2.buildtools.projectmodel.HaxeModuleWorkspace;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeContextFailures;
import com.intellij.plugins.haxe.v2.buildtools.info.HaxeDefineContextService;
import com.intellij.plugins.haxe.v2.buildtools.projectmodel.HaxeModuleSdkApplier;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import com.intellij.plugins.haxe.v2.buildtools.settings.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.toolwindow.actions.*;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowModelBuilder.ContainerEntry;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowModelBuilder.EnvironmentData;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowModelBuilder.FileEntry;
import com.intellij.plugins.haxe.v2.toolwindow.tree.*;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.*;
import com.intellij.plugins.haxe.runner.HaxeRunConfigurationType;
import com.intellij.plugins.haxe.v2.runconfig.HaxeActionConfigurationFactory;
import com.intellij.plugins.haxe.v2.runconfig.HaxeActionRunConfiguration;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.testing.run.HaxeTestRunConfigurations;
import com.intellij.plugins.haxe.v2.toolwindow.ui.HaxeCompileCommandDialog;
import com.intellij.pom.Navigatable;
import com.intellij.util.PathUtil;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.tree.TreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Content panel of the Haxe tool window: a toolbar (sync, purge caches, execute
 * command, settings) above a tree of modules, their build files and each file's
 * target, defines and library dependencies.
 */
@CustomLog
public final class HaxeToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

  public static final String TOOLBAR_PLACE = "HaxeToolWindowToolbar";
  public static final String TREE_POPUP_PLACE = "HaxeToolWindowTreePopup";


  private final Project project;
  private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
  private final Tree tree = new Tree(treeModel);
  private final HaxeToolWindowModelBuilder modelBuilder;
  private boolean initialExpansionDone;
  // last scan's tests build files per container (EDT only), for the container-row unit-test action
  private final Map<String, List<String>> testsPathsByContainer = new HashMap<>();
  private @Nullable String projectRootContainerId;

  public HaxeToolWindowPanel(@NotNull Project project) {
    super(true, true);
    this.project = project;
    this.modelBuilder = new HaxeToolWindowModelBuilder(project, this::refreshTree);

    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setCellRenderer(new HaxeToolWindowTreeRenderer());
    // per-row tooltips come from the renderer; a plain JTree never shows them unless registered
    ToolTipManager.sharedInstance().registerComponent(tree);
    tree.addMouseListener(new TreeClickHandler());
    PopupHandler.installPopupMenu(tree, createTreePopupGroup(), TREE_POPUP_PLACE);
    TreeSpeedSearch.installOn(tree, true, HaxeToolWindowPanel::speedSearchText);
    new TreeEnterAction().registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this);
    new TreeDeleteAction().registerCustomShortcutSet(CommonShortcuts.getDelete(), tree, this);

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
    // store mutations can originate outside this panel (the define quickfix
    // edits environment overrides) - the tree must follow those too
    project.getMessageBus().connect(this)
      .subscribe(HaxeBuildSettingsListener.TOPIC, (HaxeBuildSettingsListener)this::refreshTree);

    refreshTree();
  }

  @NotNull
  private JComponent createToolbar() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new HaxeSyncProjectAction(this::refreshTree));
    group.add(new HaxePurgeCachesAction(this::refreshTree));
    group.addSeparator();
    group.add(new HaxeAddModuleAction());
    group.add(new HaxeRemoveNodeAction(this));
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
    group.add(new HaxeRunUnitTestsAction(this));
    group.add(new HaxeDebugUnitTestsAction(this));
    group.add(new HaxeSetAsCompileCommandAction(this));
    group.add(new HaxeAddCustomActionAction(this));
    group.add(new HaxeEditCustomActionAction(this));
    group.add(new HaxeRemoveCustomActionAction(this));
    group.add(new HaxeSetActiveBuildFileAction(this));
    group.add(new HaxeMarkTestsBuildFileAction(this));
    group.add(new HaxeReloadBuildFileAction(this));
    group.add(new HaxeAddBuildFileAction(this));
    group.add(new HaxeRemoveBuildFileAction(this));
    group.add(new HaxeSelectTargetAction(this));
    group.add(new HaxeSelectSectionAction(this));
    group.add(new HaxeInstallLibraryAction(this));
    group.add(new HaxeInstallAllMissingLibrariesAction(this));
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
    ReadAction.nonBlocking(modelBuilder::build)
      .inSmartMode(project)
      .expireWith(this)
      .submit(AppExecutorUtil.getAppExecutorService())
      .onSuccess(scan -> AppExecutorUtil.getAppExecutorService().execute(() -> updateTree(scan)));
  }

  /** Never lets a haxelib failure prevent the tree from updating - install state just becomes unknown. */
  private void updateTree(@NotNull List<ContainerEntry> scan) {
    Map<String, HaxeToolWindowModelBuilder.InstalledLibrary> installed = null;
    try {
      installed = HaxeToolWindowModelBuilder.fetchInstalledLibraryVersions(project);
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
      rememberTestsPaths(scan);
      applyTreeUpdate(root);
    });
  }

  /** Keeps the scan's per-container tests build files, so container-row actions resolve them without re-scanning. */
  private void rememberTestsPaths(@NotNull List<ContainerEntry> scan) {
    testsPathsByContainer.clear();
    // reset alongside the map: a scan without a project-root container must
    // not leave the previous id answering for a container that is gone
    projectRootContainerId = null;
    for (ContainerEntry container : scan) {
      if (!container.testsPaths().isEmpty()) {
        testsPathsByContainer.put(container.id(), container.testsPaths());
      }
      if (container.projectRoot()) {
        projectRootContainerId = container.id();
      }
    }
  }

  /** The container's FIRST tests build file (marked or convention-suggested) from the last scan, or null - what a container-row run targets. */
  @Nullable
  private String testsPathFor(@NotNull String containerId) {
    List<String> paths = testsPathsByContainer.get(containerId);
    return paths == null || paths.isEmpty() ? null : paths.get(0);
  }

  /** The selection's tests build file: the row's own file, or the container's marked/suggested one from the last scan. */
  @Nullable
  public String resolveTestsPath(@Nullable Object selection) {
    return switch (selection) {
      case TestRunNode node -> node.buildFilePath();
      case ModuleNode module -> testsPathFor(module.name());
      case ProjectNode ignored -> projectRootContainerId == null ? null : testsPathFor(projectRootContainerId);
      case null, default -> null;
    };
  }

  /** Runs the build file as unit tests through the test run configuration (SM console). */
  public void runUnitTests(@NotNull String buildFilePath) {
    HaxeTestRunConfigurations.run(project, buildFilePath);
  }

  public void debugUnitTests(@NotNull String buildFilePath) {
    HaxeTestRunConfigurations.debug(project, buildFilePath);
  }

  /** Gradle-style structure: one project root node containing root-level build files and the modules. */
  @NotNull
  private DefaultMutableTreeNode buildTreeRoot(@NotNull List<ContainerEntry> scan,
                                               @Nullable Map<String, HaxeToolWindowModelBuilder.InstalledLibrary> installedLibraries) {
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
      ProgramNode program =
        new ProgramNode(entry.buildFile(), launchKind, entry.info().target(), entry.info().targetOutput());
      actionsNode.add(new DefaultMutableTreeNode(program));
    }
    return actionsNode;
  }

  /** The tests build file's own category, keeping test runs out of the crowded Actions group. */
  @NotNull
  private static DefaultMutableTreeNode buildTestsGroupNode(@NotNull FileEntry entry) {
    String path = entry.buildFile().file().getPath();
    DefaultMutableTreeNode testsNode = new DefaultMutableTreeNode(new TestsGroupNode());
    testsNode.add(new DefaultMutableTreeNode(new TestRunNode(path)));
    return testsNode;
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
                                                     @Nullable Map<String, HaxeToolWindowModelBuilder.InstalledLibrary> installedLibraries) {
    DefaultMutableTreeNode buildNode =
      new DefaultMutableTreeNode(new BuildGroupNode(container.id(), container.files().size()));
    addContainerFiles(buildNode, container, installedLibraries);
    return buildNode;
  }

  @NotNull
  private static DefaultMutableTreeNode buildEnvironmentNode(@NotNull ContainerEntry container) {
    EnvironmentData environment = container.environment();
    EnvironmentNode environmentRow =
      new EnvironmentNode(container.id(), container.displayName(), environment.activeBuildFileDefines());
    DefaultMutableTreeNode environmentNode = new DefaultMutableTreeNode(environmentRow);

    EnvSdkNode sdkRow = new EnvSdkNode(container.id(), environment.sdkDisplay(), environment.sdkMissing());
    environmentNode.add(new DefaultMutableTreeNode(sdkRow));

    EnvLanguageLevelNode levelRow = new EnvLanguageLevelNode(container.id(), environment.languageLevelDisplay());
    environmentNode.add(new DefaultMutableTreeNode(levelRow));

    EnvDefinesNode definesRow = new EnvDefinesNode(container.id(), environment.defines().size());
    DefaultMutableTreeNode definesNode = new DefaultMutableTreeNode(definesRow);

    for (EnvDefineNode define : environment.defines()) {
      definesNode.add(new DefaultMutableTreeNode(define));
    }
    environmentNode.add(definesNode);
    return environmentNode;
  }

  private void addContainerFiles(@NotNull DefaultMutableTreeNode parentNode,
                                 @NotNull ContainerEntry container,
                                 @Nullable Map<String, HaxeToolWindowModelBuilder.InstalledLibrary> installedLibraries) {
    for (FileEntry fileEntry : container.files()) {
      String path = fileEntry.buildFile().file().getPath();
      boolean active = path.equals(container.activePath());
      boolean tests = container.testsPaths().contains(path);
      // pure list scan over the prepared model - safe on the EDT
      boolean frameworkDetected = HaxeTestFrameworks.detectedFramework(fileEntry.info().libraries()) != null;
      BuildFileRow row =
        new BuildFileRow(fileEntry.buildFile(), container.id(), active, fileEntry.manual(), tests, frameworkDetected);
      parentNode.add(buildFileNode(fileEntry, row, installedLibraries));
    }
  }

  @NotNull
  private DefaultMutableTreeNode buildFileNode(@NotNull FileEntry entry,
                                               @NotNull BuildFileRow row,
                                               @Nullable Map<String, HaxeToolWindowModelBuilder.InstalledLibrary> installedLibraries) {
    HaxeBuildFile buildFile = entry.buildFile();
    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(row);
    // a plain hxp script decides its own targets in code - no target row
    if (buildFile.type() != HaxeBuildFileType.HXP_SCRIPT) {
      fileNode.add(new DefaultMutableTreeNode(buildTargetNode(entry)));
    }
    // multi-section hxml (--next chain): the row picking which compilation the tree follows
    if (!entry.sectionLabels().isEmpty()) {
      SectionNode sectionNode =
        new SectionNode(buildFile, entry.sectionIds(), entry.sectionLabels(), entry.selectedSection());
      fileNode.add(new DefaultMutableTreeNode(sectionNode));
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
      var installedLibrary = installedLibraries != null ? installedLibraries.get(key) : null;
      // a pinned version must itself be installed - the name alone is not enough
      boolean pinSatisfied = library.version() == null
                             || (installedLibrary != null && installedLibrary.versions().contains(library.version()));
      boolean installed = installedLibraries == null || (installedLibrary != null && pinSatisfied);
      String resolvedVersion = installedLibrary != null ? installedLibrary.selectedVersion() : null;
      LibraryNode libraryRow = new LibraryNode(buildFile, library.name(), library.version(), resolvedVersion, installed);
      librariesNode.add(new DefaultMutableTreeNode(libraryRow));
    }
    fileNode.add(librariesNode);
    fileNode.add(buildActionsGroupNode(entry));
    if (row.testsFile()) {
      fileNode.add(buildTestsGroupNode(entry));
    }
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

  /** Shows the {@code --next} section dropdown for a multi-section hxml, anchored at the given point. */
  public void showSectionPopup(@NotNull SectionNode sectionNode, @NotNull RelativePoint point) {
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(sectionNode.labels())
      .setTitle(HaxeBundle.message("haxe.toolwindow.select.section.title"))
      .setRenderer(BuilderKt.textListCellRenderer("", label -> label))
      .setItemChosenCallback(label -> {
        String sectionId = sectionNode.ids().get(sectionNode.labels().indexOf(label));
        HaxeSectionSelectionStore.getInstance(project).setSelectedSection(sectionNode.buildFile().file(), sectionId);
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
    String defaultDisplay = HaxeBundle.message("haxe.toolwindow.node.environment.sdk.default",
                                               compilerSettings.getDefaultLanguageLevel(levelNode.containerId()).getPresentableText());
    choices.add(new LevelChoice(null, defaultDisplay));
    for (HaxeLanguageLevel level : HaxeLanguageLevel.values()) {
      choices.add(new LevelChoice(level, level.getPresentableText()));
    }
    JBPopupFactory.getInstance()
      .createPopupChooserBuilder(choices)
      .setTitle(HaxeBundle.message("haxe.toolwindow.select.language.level.title"))
      .setRenderer(BuilderKt.textListCellRenderer("", LevelChoice::display))
      .setItemChosenCallback(choice -> {
        compilerSettings.setModuleLanguageLevelOverride(levelNode.containerId(), choice.level());
        // the define context derives haxe_ver from the level; the daemon
        // restart refreshes level-gated highlighting
        project.getMessageBus().syncPublisher(HaxeBuildConfigListener.TOPIC).buildConfigurationChanged();
        DaemonCodeAnalyzer.getInstance(project).restart("haxe: language level changed");
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

  /**
   * The missing library rows of the selection's Libraries group — the group row
   * itself, or any MISSING library row in it (siblings included); empty otherwise.
   */
  @NotNull
  public List<LibraryNode> getSelectedGroupMissingLibraries() {
    TreePath path = tree.getSelectionPath();
    if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return List.of();

    DefaultMutableTreeNode groupNode = null;
    if (node.getUserObject() instanceof GroupNode group && group.kind() == GroupKind.LIBRARIES) {
      groupNode = node;
    }
    else if (node.getUserObject() instanceof LibraryNode library && !library.installed()
             && node.getParent() instanceof DefaultMutableTreeNode parent) {
      groupNode = parent;
    }
    if (groupNode == null) return List.of();

    List<LibraryNode> missing = new ArrayList<>();
    for (int i = 0; i < groupNode.getChildCount(); i++) {
      if (groupNode.getChildAt(i) instanceof DefaultMutableTreeNode child
          && child.getUserObject() instanceof LibraryNode library
          && !library.installed()) {
        missing.add(library);
      }
    }
    return missing;
  }

  /** The text speed search matches against - the row's primary label as rendered. */
  @NotNull
  private static String speedSearchText(@NotNull TreePath path) {
    if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode treeNode)) return "";
    return treeNode.getUserObject() instanceof HaxeToolWindowNode node ? node.speedSearchText() : "";
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
          HaxeConsoleCommandRunner.run(project, compileCommand.display(), command, compileCommand.workDirectory());
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
    // ExecutionUtil (not ProgramRunnerUtil) routes through restartRunProfile,
    // which enforces single-instance configurations with the stop-and-rerun dialog
    ExecutionUtil.runConfiguration(settings, executor);
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
    Module module = ReadAction.computeBlocking(() -> ProjectFileIndex.getInstance(project).getModuleForFile(file));
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
    // ExecutionUtil (not ProgramRunnerUtil) routes through restartRunProfile,
    // which enforces single-instance configurations with the stop-and-rerun dialog
    ExecutionUtil.runConfiguration(settings, executor);
  }

  private void notifyUser(@NotNull String message) {
    HaxeCommandNotifications.notify(project, message, NotificationType.WARNING);
  }

  /** Toggles the container's server participation; while disabled project-wide, opens the settings page instead. */
  public void toggleCompilationServer(@NotNull CompilationServerNode serverNode) {
    if (!serverNode.projectEnabled()) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, HaxeBuildToolsConfigurable.class);
    }
    else {
      boolean enable = !serverNode.moduleUses();
      HaxeEnvironmentStore.getInstance(project).setUsingCompilationServer(serverNode.containerId(), enable);
      if (!enable) {
        // an opted-out container sends no more requests - a lingering failure could never clear itself
        HaxeContextFailures.getInstance(project).record(serverNode.containerId(), null);
      }
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
    return userObject instanceof HaxeToolWindowNode node ? node.expansionKey() : String.valueOf(userObject);
  }

  private final class TreeClickHandler extends MouseAdapter {
    @Override
    public void mousePressed(MouseEvent e) {
      selectRowForPopup(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      selectRowForPopup(e);
    }

    /**
     * A right-click acts on the row under the cursor: select it BEFORE the
     * popup handler evaluates the menu actions (a JTree does not select on
     * right-click by itself, leaving every selection-driven action hidden).
     * Both pressed and released matter - the popup trigger fires on press or
     * release depending on the platform.
     */
    private void selectRowForPopup(MouseEvent e) {
      if (!e.isPopupTrigger()) return;
      TreePath path = tree.getPathForLocation(e.getX(), e.getY());
      if (path != null && !tree.isPathSelected(path)) {
        tree.setSelectionPath(path);
      }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
      if (!SwingUtilities.isLeftMouseButton(e)) return;
      TreePath path = tree.getPathForLocation(e.getX(), e.getY());
      if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return;

      int clicks = e.getClickCount();
      Object userObject = node.getUserObject();
      RelativePoint point = new RelativePoint(e.getComponent(), e.getPoint());

      if (clicks == 1) {
        interactWithNode(userObject, point, fragmentTagAt(e, path));
      } else if (clicks == 2) {
        activateNode(userObject);
      }
    }

    /** The renderer fragment tag under the click — tags mark clickable fragments (the server failure link). */
    @Nullable
    private Object fragmentTagAt(@NotNull MouseEvent e, @NotNull TreePath path) {
      Rectangle bounds = tree.getPathBounds(path);
      if (bounds == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) return null;
      Component renderer = tree.getCellRenderer()
        .getTreeCellRendererComponent(tree, node, tree.isPathSelected(path), tree.isExpanded(path),
                                      node.isLeaf(), tree.getRowForPath(path), false);
      if (!(renderer instanceof SimpleColoredComponent colored)) return null;
      renderer.setSize(bounds.width, bounds.height);
      return colored.getFragmentTagAt(e.getX() - bounds.x);
    }

  }

  /** Opens the server console at the tab serving this container's SDK — the status view holds the failure detail. */
  private void openServerConsole(@NotNull String containerId) {
    String sdkName = HaxeToolPathResolver.effectiveSdkName(project, containerId);
    String serverId = HaxeToolPathResolver.resolveHaxeExecutable(project, sdkName);
    HaxeServerConsoleWindowFactory.open(project, serverId);
  }

  /** The row's ACTIVATION — double-click and Enter share it. False when the row has none. */
  private boolean activateNode(@Nullable Object userObject) {
    switch (userObject) {
      case BuildFileRow row when row.buildFile().file().isValid() ->
        new OpenFileDescriptor(project, row.buildFile().file()).navigate(true);
      case ActionNode actionNode -> runAction(actionNode);
      case ProgramNode programNode -> executeProgram(programNode, false);
      case TestRunNode testRunNode -> runUnitTests(testRunNode.buildFilePath());
      case null, default -> {
        return false;
      }
    }
    return true;
  }

  /** The row's chooser/toggle INTERACTION — single-click and Enter share it. False when the row has none. */
  private boolean interactWithNode(@Nullable Object userObject, @NotNull RelativePoint point, @Nullable Object fragmentTag) {
    switch (userObject) {
      case TargetNode targetNode when targetNode.selectable() -> showTargetPopup(targetNode, point);
      case SectionNode sectionNode -> showSectionPopup(sectionNode, point);
      case EnvSdkNode sdkNode -> showEnvironmentSdkPopup(sdkNode, point);
      case EnvLanguageLevelNode levelNode -> showLanguageLevelPopup(levelNode, point);
      case EnvCompileCommandNode buildCommand -> configureCompileCommand(buildCommand);
      case CompilationServerNode serverNode -> {
        // the red failure text links to the server console's status view;
        // the rest of the row keeps the participation toggle
        if (fragmentTag instanceof HaxeToolWindowNodes.ServerFailureLink link) {
          openServerConsole(link.containerId());
        }
        else {
          toggleCompilationServer(serverNode);
        }
      }
      case null, default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Enter mirrors the mouse: a row's activation (run, launch, open) first,
   * its chooser/toggle second, and rows with neither toggle their expansion.
   */
  private final class TreeEnterAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Object selected = getSelectedUserObject();
      if (activateNode(selected)) return;
      if (interactWithNode(selected, getSelectionPopupPoint(), null)) return;
      toggleSelectedExpansion();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(tree.getSelectionPath() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  /** Delete removes the selected removable row — build file, define override, module — after confirmation. */
  private final class TreeDeleteAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      switch (getSelectedUserObject()) {
        case BuildFileRow row -> confirmAndRemoveBuildFile(row);
        case EnvDefineNode define -> confirmAndRemoveDefine(define);
        case ModuleNode module -> confirmAndRemoveModule(module);
        case null, default -> { }
      }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Object selected = getSelectedUserObject();
      boolean removable = selected instanceof BuildFileRow
                          || selected instanceof EnvDefineNode
                          || selected instanceof ModuleNode;
      e.getPresentation().setEnabled(removable);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  public void confirmAndRemoveBuildFile(@NotNull BuildFileRow row) {
    String confirmKey = row.manual() ? "haxe.toolwindow.remove.build.file.confirm"
                                     : "haxe.toolwindow.hide.build.file.confirm";
    String titleKey = row.manual() ? "haxe.toolwindow.remove.build.file" : "haxe.toolwindow.hide.build.file";
    int answer = Messages.showYesNoDialog(project,
                                          HaxeBundle.message(confirmKey, row.buildFile().file().getName()),
                                          HaxeBundle.message(titleKey),
                                          Messages.getQuestionIcon());
    if (answer != Messages.YES) return;
    HaxeBuildFilesStore.getInstance(project).removeFile(row.containerId(), row.buildFile().file().getPath());
    refreshTree();
  }

  private void confirmAndRemoveDefine(@NotNull EnvDefineNode define) {
    int answer = Messages.showYesNoDialog(project,
                                          HaxeBundle.message("haxe.toolwindow.remove.define.confirm", define.name()),
                                          HaxeBundle.message("haxe.toolwindow.remove.define"),
                                          Messages.getQuestionIcon());
    if (answer != Messages.YES) return;
    HaxeEnvironmentStore.getInstance(project).removeDefine(define.containerId(), define.name());
    refreshTree();
  }

  /** Module removal after confirmation. Files on disk are untouched - the folder folds back into the surrounding module. */
  public void confirmAndRemoveModule(@NotNull ModuleNode module) {
    int answer = Messages.showYesNoDialog(
      project,
      HaxeBundle.message("haxe.toolwindow.remove.module.confirm", module.name()),
      HaxeBundle.message("haxe.toolwindow.remove.module"),
      Messages.getWarningIcon());
    if (answer != Messages.YES) return;
    HaxeModuleWorkspace.getInstance(project).removeModuleAsync(module.name());
  }

  private void toggleSelectedExpansion() {
    TreePath path = tree.getSelectionPath();
    if (path == null) return;
    if (tree.isExpanded(path)) {
      tree.collapsePath(path);
    }
    else {
      tree.expandPath(path);
    }
  }

  @Override
  public void dispose() {
    // message bus connection is tied to this Disposable; nothing else to release
  }
}
