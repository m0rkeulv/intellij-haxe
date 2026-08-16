package com.intellij.plugins.haxe.ide.toolWindow.haxelib;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.haxelib.HaxelibCacheManager;
import com.intellij.plugins.haxe.haxelib.HaxelibInstalledIndex;
import com.intellij.plugins.haxe.haxelib.HaxelibLibraryInfo;
import com.intellij.plugins.haxe.haxelib.HaxelibLocalDocs;
import com.intellij.plugins.haxe.haxelib.HaxelibSdkUtils;
import com.intellij.plugins.haxe.haxelib.HaxelibUtil;
import com.intellij.plugins.haxe.ide.documentation.HaxeDocumentationRenderer;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.components.BorderLayoutPanel;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Haxelib tool window's permanent Explorer tab: a library TREE (each
 * library expands to its versions, installed ones marked) beside the selected
 * library's tabbed details, with an always-visible vertical filter strip on
 * the left edge. The installed picture comes from the MODULE's haxelib
 * repository (a local {@code .haxelib} when one exists), catalog and
 * per-library metadata from the session-cached {@link HaxelibCacheManager};
 * Refresh is the explicit force-update. All haxelib calls run on pooled
 * threads; a library's version children materialize on first expand.
 */
// TODO multi-module projects: the explorer follows the FIRST module with a
//  valid Haxe SDK; a module chooser belongs in the toolbar once a real
//  project needs per-module repositories.
public final class HaxelibExplorerPanel extends BorderLayoutPanel implements Disposable {

  /** One library with its LOCAL state (empty versions = not installed). */
  record LibraryRow(@NotNull String name,
                    @NotNull Set<String> installedVersions,
                    @Nullable String selectedVersion) {
    boolean installed() {
      return !installedVersions.isEmpty();
    }

    boolean dev() {
      return installedVersions.contains("dev");
    }

    boolean git() {
      return installedVersions.contains("git");
    }

    /** Whether a plain RELEASE version is installed (dev/git pseudo-versions aside). */
    boolean installedRelease() {
      return installedVersions.stream().anyMatch(v -> !"dev".equals(v) && !"git".equals(v));
    }
  }

  /** One version under a library node. */
  record VersionEntry(@NotNull String library,
                      @NotNull String version,
                      @Nullable String date,
                      @Nullable String note,
                      boolean installed,
                      boolean current) {
  }

  private final Project project;
  private final SearchTextField searchField = new SearchTextField();
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
  private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
  private final Tree tree = new Tree(treeModel);
  private final HaxelibDetailsPane details =
    new HaxelibDetailsPane(HaxeBundle.message("haxelib.explorer.tab.overview"));
  private final HaxelibExplorerFilters filters = new HaxelibExplorerFilters(this::refilter);
  private final HaxeDocumentationRenderer markdownRenderer;

  private volatile List<LibraryRow> allRows = List.of();
  // separate guards: a selection click must not discard an in-flight catalog
  // load's second stage (one shared counter did exactly that)
  private final AtomicInteger loadGeneration = new AtomicInteger();
  private final AtomicInteger selectionGeneration = new AtomicInteger();
  private volatile boolean disposed;

  public HaxelibExplorerPanel(@NotNull Project project) {
    this.project = project;
    this.markdownRenderer = new HaxeDocumentationRenderer(project);

    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    tree.setCellRenderer(new NodeRenderer());
    tree.addTreeSelectionListener(e -> showSelection());
    tree.addTreeWillExpandListener(new LazyVersionLoader());
    PopupHandler.installPopupMenu(tree, HaxelibExplorerActions.createGroup(this), "HaxelibExplorerPopup");

    searchField.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        refilter();
      }
    });

    BorderLayoutPanel treePanel = new BorderLayoutPanel();
    treePanel.addToTop(createToolbarRow());
    treePanel.addToCenter(new JBScrollPane(tree));
    treePanel.addToLeft(createFilterStrip());

    OnePixelSplitter splitter = new OnePixelSplitter(false, 0.4f);
    splitter.setFirstComponent(treePanel);
    splitter.setSecondComponent(details.getComponent());
    addToCenter(splitter);

    reload(false);
  }

  @NotNull
  private BorderLayoutPanel createToolbarRow() {
    DefaultActionGroup actions = new DefaultActionGroup();
    actions.add(new RefreshAction());
    ActionToolbar toolbar = ActionManager.getInstance()
      .createActionToolbar("HaxelibExplorer", actions, true);
    toolbar.setTargetComponent(this);

    BorderLayoutPanel row = new BorderLayoutPanel();
    row.addToCenter(searchField);
    row.addToRight(toolbar.getComponent());
    return row;
  }

  @NotNull
  private JComponent createFilterStrip() {
    ActionToolbar strip = ActionManager.getInstance()
      .createActionToolbar("HaxelibExplorerFilters", filters.createToggleGroup(), false);
    strip.setTargetComponent(this);
    return strip.getComponent();
  }

  // ------------------------------------------------------------- loading

  /** Loads installed (fast, repo-scoped) then the online catalog, updating the tree after each stage. */
  private void reload(boolean force) {
    int expected = loadGeneration.incrementAndGet();
    details.showMessage(HaxeBundle.message("haxelib.explorer.loading"));
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      HaxelibCacheManager manager = cacheManager();
      if (manager == null) {
        onUi(loadGeneration, expected, () -> details.showMessage(HaxeBundle.message("haxelib.explorer.no.sdk")));
        return;
      }
      if (force) {
        manager.forceReload();
      }
      HaxelibInstalledIndex installed = manager.getInstalledIndex();
      onUi(loadGeneration, expected, () -> setRows(buildRows(installed, Map.of())));

      Map<String, Set<String>> catalog = manager.getAvailableLibraries();
      onUi(loadGeneration, expected, () -> setRows(buildRows(installed, catalog)));
    });
  }

  /** Re-reads the installed picture (cheap) and rebuilds — the after-mutation refresh. */
  void reloadAfterMutation() {
    HaxelibCacheManager manager = cacheManager();
    if (manager != null) {
      manager.refreshInstalled();
    }
    reload(false);
  }

  @NotNull
  private static List<LibraryRow> buildRows(@NotNull HaxelibInstalledIndex installed,
                                            @NotNull Map<String, Set<String>> catalog) {
    List<LibraryRow> rows = new ArrayList<>();
    for (String name : installed.getInstalledLibraries()) {
      rows.add(new LibraryRow(name, installed.getInstalledVersions(name), installed.getSelectedVersion(name)));
    }
    for (String name : catalog.keySet()) {
      if (!installed.getInstalledLibraries().contains(name)) {
        rows.add(new LibraryRow(name, Set.of(), null));
      }
    }
    // installed first, alphabetical within each group
    rows.sort(Comparator.comparing((LibraryRow row) -> !row.installed())
                .thenComparing(LibraryRow::name, String.CASE_INSENSITIVE_ORDER));
    return rows;
  }

  private void setRows(@NotNull List<LibraryRow> rows) {
    allRows = rows;
    refilter();
  }

  private void refilter() {
    Set<String> expandedLibraries = collectExpandedLibraries();
    String selectedKey = selectionKey(tree.getSelectionPath());

    String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
    // resolved once - the row loop must not repeat the module/SDK lookup
    HaxelibCacheManager manager = cacheManager();
    root.removeAllChildren();
    for (LibraryRow row : allRows) {
      if (accepted(row, query, manager)) {
        DefaultMutableTreeNode libraryNode = new DefaultMutableTreeNode(row);
        // a placeholder gives the node its expand handle; real children
        // materialize on expand (the release list needs one info fetch)
        libraryNode.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
        root.add(libraryNode);
      }
    }
    treeModel.reload();
    restoreTreeState(expandedLibraries, selectedKey);
  }

  // ------------------------------------------------- expansion preservation

  /**
   * Rebuilds happen on every filter change and after every mutation; the
   * user's expansion and selection survive them by STABLE identity (library
   * name, library|version) — re-expanding goes through the lazy loader, so
   * version children refresh from the new installed state in place.
   */
  private void restoreTreeState(@NotNull Set<String> expandedLibraries, @Nullable String selectedKey) {
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode libraryNode = (DefaultMutableTreeNode)root.getChildAt(i);
      if (!(libraryNode.getUserObject() instanceof LibraryRow row)) continue;
      TreePath libraryPath = new TreePath(libraryNode.getPath());
      if (expandedLibraries.contains(row.name())) {
        tree.expandPath(libraryPath);
      }
      restoreSelection(libraryNode, row, libraryPath, selectedKey);
    }
  }

  private void restoreSelection(@NotNull DefaultMutableTreeNode libraryNode, @NotNull LibraryRow row,
                                @NotNull TreePath libraryPath, @Nullable String selectedKey) {
    if (selectedKey == null) return;
    if (selectedKey.equals(row.name())) {
      tree.setSelectionPath(libraryPath);
      return;
    }
    if (!selectedKey.startsWith(row.name() + "|")) return;
    // a version selection: the exact child when the (cached-info) expand
    // already materialized it, else the library node stands in
    for (int i = 0; i < libraryNode.getChildCount(); i++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode)libraryNode.getChildAt(i);
      if (child.getUserObject() instanceof VersionEntry entry
          && selectedKey.equals(entry.library() + "|" + entry.version())) {
        tree.setSelectionPath(new TreePath(child.getPath()));
        return;
      }
    }
    tree.setSelectionPath(libraryPath);
  }

  @NotNull
  private Set<String> collectExpandedLibraries() {
    Set<String> names = new LinkedHashSet<>();
    var expanded = tree.getExpandedDescendants(new TreePath(root));
    while (expanded != null && expanded.hasMoreElements()) {
      Object last = ((DefaultMutableTreeNode)expanded.nextElement().getLastPathComponent()).getUserObject();
      if (last instanceof LibraryRow row) {
        names.add(row.name());
      }
    }
    return names;
  }

  @Nullable
  private static String selectionKey(@Nullable TreePath path) {
    if (path == null) return null;
    Object userObject = ((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
    if (userObject instanceof LibraryRow row) return row.name();
    if (userObject instanceof VersionEntry entry) return entry.library() + "|" + entry.version();
    return null;
  }

  /** A library node stays while ANY of its versions would be visible under the version filter. */
  private boolean accepted(@NotNull LibraryRow row, @NotNull String query, @Nullable HaxelibCacheManager manager) {
    boolean anyVersionShown =
      (filters.isActive(HaxelibExplorerFilters.Filter.INSTALLED) && row.installedRelease())
      || (filters.isActive(HaxelibExplorerFilters.Filter.NOT_INSTALLED) && hasNotInstalledVersion(row, manager))
      || (filters.isActive(HaxelibExplorerFilters.Filter.DEV) && row.dev())
      || (filters.isActive(HaxelibExplorerFilters.Filter.GIT) && row.git());
    if (!anyVersionShown) return false;
    if (filters.isActive(HaxelibExplorerFilters.Filter.ONLY_UPDATES) && !hasKnownUpdate(row, manager)) return false;
    return query.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(query);
  }

  /** The filter applied to each version node: visible while its kind's toggle is on. */
  private boolean versionShown(@NotNull VersionEntry entry) {
    if ("dev".equals(entry.version())) return filters.isActive(HaxelibExplorerFilters.Filter.DEV);
    if ("git".equals(entry.version())) return filters.isActive(HaxelibExplorerFilters.Filter.GIT);
    return filters.isActive(entry.installed() ? HaxelibExplorerFilters.Filter.INSTALLED
                                              : HaxelibExplorerFilters.Filter.NOT_INSTALLED);
  }

  // without fetched info the release list is unknown; keep the node visible
  // and let the version filter prune children when they materialize
  private boolean hasNotInstalledVersion(@NotNull LibraryRow row, @Nullable HaxelibCacheManager manager) {
    HaxelibLibraryInfo info = manager == null ? null : manager.getCachedLibraryInfo(row.name());
    if (info == null) return true;
    return info.releases().stream().anyMatch(release -> !row.installedVersions().contains(release.version()));
  }

  // only answerable from already-cached info: the filter never triggers a
  // server sweep over the whole catalog
  private boolean hasKnownUpdate(@NotNull LibraryRow row, @Nullable HaxelibCacheManager manager) {
    if (!row.installed() || row.dev() || row.git()) return false;
    HaxelibLibraryInfo info = manager == null ? null : manager.getCachedLibraryInfo(row.name());
    return info != null && !info.latestVersion().isEmpty()
           && !row.installedVersions().contains(info.latestVersion());
  }

  // ----------------------------------------------------- version children

  private static final String LOADING_PLACEHOLDER = HaxeBundle.message("haxelib.explorer.loading.versions");

  private final class LazyVersionLoader implements TreeWillExpandListener {
    @Override
    public void treeWillExpand(TreeExpansionEvent event) {
      if (!(event.getPath().getLastPathComponent() instanceof DefaultMutableTreeNode node)
          || !(node.getUserObject() instanceof LibraryRow row)
          || !isPlaceholderOnly(node)) {
        return;
      }
      HaxelibCacheManager manager = cacheManager();
      if (manager == null) return;
      HaxelibLibraryInfo cached = manager.getCachedLibraryInfo(row.name());
      if (cached != null) {
        setVersionChildren(node, row, cached);
        return;
      }
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        HaxelibLibraryInfo info = manager.getLibraryInfo(row.name());
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!disposed && isPlaceholderOnly(node)) {
            setVersionChildren(node, row, info);
          }
        });
      });
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) {
    }
  }

  private static boolean isPlaceholderOnly(@NotNull DefaultMutableTreeNode node) {
    return node.getChildCount() == 1
           && ((DefaultMutableTreeNode)node.getChildAt(0)).getUserObject() == LOADING_PLACEHOLDER;
  }

  private void setVersionChildren(@NotNull DefaultMutableTreeNode node,
                                  @NotNull LibraryRow row,
                                  @Nullable HaxelibLibraryInfo info) {
    node.removeAllChildren();
    for (VersionEntry entry : versionEntries(row, info)) {
      if (versionShown(entry)) {
        node.add(new DefaultMutableTreeNode(entry));
      }
    }
    treeModel.reload(node);
    tree.expandPath(new TreePath(node.getPath()));
  }

  /** The version list under a library: dev/git and local-only versions first, then every release newest-first. */
  @NotNull
  static List<VersionEntry> versionEntries(@NotNull LibraryRow row, @Nullable HaxelibLibraryInfo info) {
    List<VersionEntry> entries = new ArrayList<>();
    Set<String> covered = new LinkedHashSet<>();
    for (String pseudo : List.of("dev", "git")) {
      if (row.installedVersions().contains(pseudo)) {
        entries.add(entry(row, pseudo, null, null));
        covered.add(pseudo);
      }
    }
    List<HaxelibLibraryInfo.Release> releases = info == null ? List.of() : info.releases();
    Set<String> released = new LinkedHashSet<>();
    releases.forEach(release -> released.add(release.version()));
    for (String local : row.installedVersions()) {
      if (!covered.contains(local) && !released.contains(local)) {
        entries.add(entry(row, local, null, null));
        covered.add(local);
      }
    }
    for (HaxelibLibraryInfo.Release release : releases.reversed()) {
      entries.add(entry(row, release.version(), release.date(), release.note()));
    }
    return entries;
  }

  private static VersionEntry entry(@NotNull LibraryRow row, @NotNull String version,
                                    @Nullable String date, @Nullable String note) {
    return new VersionEntry(row.name(), version, date, note,
                            row.installedVersions().contains(version),
                            version.equals(row.selectedVersion()));
  }

  // ------------------------------------------------------------ selection

  private void showSelection() {
    int expected = selectionGeneration.incrementAndGet();
    LibraryRow row = selectedLibraryRow();
    if (row == null) {
      details.showMessage(HaxeBundle.message("haxelib.explorer.no.selection"));
      return;
    }
    // one loading state, then ONE render with everything ready - an
    // intermediate overview-only render would steal the tab selection from
    // the readme
    details.showMessage(HaxeBundle.message("haxelib.explorer.loading.details"));
    String docsVersion = docsVersionFor(row);
    HaxelibCacheManager manager = cacheManager();
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      HaxelibLibraryInfo info = manager == null ? null : manager.getLibraryInfo(row.name());
      List<HaxelibDetailsPane.DocTab> docs = docsVersion == null ? List.of() : loadDocs(row.name(), docsVersion);
      onUi(selectionGeneration, expected,
           () -> details.showLibrary(row.name(), row.installedVersions(), row.selectedVersion(), info, docs));
    });
  }

  /** The version whose local files feed the doc tabs: the selected version node, else the library's current one. */
  @Nullable
  private String docsVersionFor(@NotNull LibraryRow row) {
    if (selectedUserObject() instanceof VersionEntry entry && entry.installed()) {
      return entry.version();
    }
    return row.selectedVersion();
  }

  @NotNull
  private List<HaxelibDetailsPane.DocTab> loadDocs(@NotNull String name, @NotNull String version) {
    Path repoRoot = repositoryRoot();
    if (repoRoot == null) return List.of();
    Path directory = HaxelibLocalDocs.versionDirectory(repoRoot, name, version);
    if (directory == null) return List.of();
    List<HaxelibDetailsPane.DocTab> docs = new ArrayList<>();
    URL base = directoryUrl(directory);
    for (Path file : HaxelibLocalDocs.docFiles(directory)) {
      try {
        String markdown = Files.readString(file);
        // the renderer's code-fence highlighting lexes through the
        // platform's editor machinery, which requires the read lock even
        // off the EDT
        String rendered = ReadAction.nonBlocking(() -> markdownRenderer.parseAndRender(markdown))
          .executeSynchronously();
        String html = "<html><body>" + adaptImagesForSwing(rendered) + "</body></html>";
        docs.add(new HaxelibDetailsPane.DocTab(file.getFileName().toString(), html, base));
      }
      catch (IOException ignored) {
        // an unreadable doc file just contributes no tab
      }
    }
    return docs;
  }

  /**
   * Swing's HTML viewer draws a colored border around an image inside a
   * link unless the img carries border=0, and cannot decode SVG at all —
   * the typical CI/version badges would each render as a broken-image box,
   * so those are dropped.
   */
  @NotNull
  private static String adaptImagesForSwing(@NotNull String html) {
    // an entire <img ...> tag whose src attribute points at an .svg file or
    // a shields.io badge (served as SVG regardless of extension)
    String withoutSvg = html.replaceAll("<img[^>]*src=\"[^\"]*(?:\\.svg|img\\.shields\\.io)[^\"]*\"[^>]*>", "");
    return withoutSvg.replace("<img ", "<img border=\"0\" ");
  }

  @Nullable
  private static URL directoryUrl(@NotNull Path directory) {
    try {
      return directory.toUri().toURL();
    }
    catch (MalformedURLException e) {
      return null;
    }
  }

  @Nullable
  private Path repositoryRoot() {
    // SDK and VFS resolution read the project model - pooled callers need the read lock
    return ReadAction.nonBlocking(() -> {
      Module module = haxeModule();
      if (module == null) return null;
      Sdk sdk = HaxelibSdkUtils.lookupSdk(module);
      VirtualFile moduleDir = ProjectUtil.guessModuleDir(module);
      VirtualFile root = HaxelibUtil.getLibraryBasePath(sdk, moduleDir);
      return root == null ? null : Path.of(root.getPath());
    }).executeSynchronously();
  }

  // ------------------------------------------------------------- context

  @Nullable
  Object selectedUserObject() {
    TreePath path = tree.getSelectionPath();
    return path == null ? null : ((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
  }

  /** The library of the selection — the node itself or the parent of a version node. */
  @Nullable
  LibraryRow selectedLibraryRow() {
    TreePath path = tree.getSelectionPath();
    if (path == null) return null;
    for (Object component : path.getPath()) {
      if (((DefaultMutableTreeNode)component).getUserObject() instanceof LibraryRow row) {
        return row;
      }
    }
    return null;
  }

  @NotNull
  Project getProject() {
    return project;
  }

  /** The cache behind the first module with a valid Haxe SDK; null without one. */
  @Nullable
  private HaxelibCacheManager cacheManager() {
    Module module = haxeModule();
    return module == null ? null : HaxelibCacheManager.getInstance(module);
  }

  @Nullable
  private Module haxeModule() {
    // module and SDK lookups read the project model - pooled callers need the read lock
    return ReadAction.computeBlocking(() -> {
      for (Module module : ModuleManager.getInstance(project).getModules()) {
        if (HaxelibSdkUtils.isValidHaxeSdk(HaxelibSdkUtils.lookupSdk(module))) {
          return module;
        }
      }
      return null;
    });
  }

  private void onUi(@NotNull AtomicInteger guard, int expectedGeneration, @NotNull Runnable update) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!disposed && guard.get() == expectedGeneration) {
        update.run();
      }
    });
  }

  @Override
  public void dispose() {
    disposed = true;
  }

  // ------------------------------------------------------------ rendering

  private static final class NodeRenderer extends ColoredTreeCellRenderer {
    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
      Object userObject = ((DefaultMutableTreeNode)value).getUserObject();
      if (userObject instanceof LibraryRow library) {
        setIcon(library.installed() ? AllIcons.Nodes.PpLib : AllIcons.Nodes.PpLibFolder);
        append(library.name());
        if (library.selectedVersion() != null) {
          append("  " + library.selectedVersion(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
      }
      else if (userObject instanceof VersionEntry entry) {
        setIcon(entry.installed() ? AllIcons.Actions.Checked : AllIcons.Actions.Download);
        append(entry.version(), entry.current() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                                                : SimpleTextAttributes.REGULAR_ATTRIBUTES);
        if (entry.date() != null) {
          append("  " + entry.date(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        if (entry.note() != null && !entry.note().isEmpty()) {
          append("  " + entry.note(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
        }
      }
      else {
        append(String.valueOf(userObject), SimpleTextAttributes.GRAYED_ATTRIBUTES);
      }
    }
  }

  private final class RefreshAction extends AnAction implements DumbAware {
    private RefreshAction() {
      super(() -> HaxeBundle.message("haxelib.explorer.refresh"), AllIcons.Actions.Refresh);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      reload(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }
}
