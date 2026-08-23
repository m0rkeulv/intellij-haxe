package com.intellij.plugins.haxe.ide.toolWindow.haxelib;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.haxelib.HaxelibLocalDocs;
import com.intellij.plugins.haxe.haxelib.HaxelibSemVer;
import com.intellij.plugins.haxe.ide.toolWindow.haxelib.HaxelibExplorerPanel.LibraryRow;
import com.intellij.plugins.haxe.ide.toolWindow.haxelib.HaxelibExplorerPanel.VersionEntry;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCommandNotifications;
import com.intellij.plugins.haxe.v2.buildtools.libraries.HaxeLibrarySync;
import com.intellij.plugins.haxe.v2.buildtools.libraries.HaxelibInstaller;
import java.util.Comparator;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The explorer tree's context menu: install/remove/set-current on a VERSION
 * node, install-latest/remove-all on a LIBRARY node. Every mutation runs in
 * the background through {@link HaxelibInstaller}, then refreshes the
 * installed picture and kicks the v2 library sync so External Libraries and
 * resolve follow immediately.
 */
final class HaxelibExplorerActions {

  private HaxelibExplorerActions() {
  }

  @NotNull
  static DefaultActionGroup createGroup(@NotNull HaxelibExplorerPanel panel) {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new InstallAndSetCurrent(panel));
    group.add(new InstallVersion(panel));
    group.add(new SetCurrent(panel));
    group.add(new RemoveVersion(panel));
    group.addSeparator();
    group.add(new InstallLatest(panel));
    group.add(new RemoveLibrary(panel));
    group.add(new AddLibrary(panel));
    group.addSeparator();
    group.add(new SetDevDirectory(panel));
    group.add(new RemoveDevDirectory(panel));
    group.add(new InstallFromGitRepository(panel));
    return group;
  }

  /** The tree toolbar's plus button — the same add flow the context menu offers (icon-less there). */
  @NotNull
  static AnAction createAddLibraryAction(@NotNull HaxelibExplorerPanel panel) {
    AnAction action = new AddLibrary(panel);
    action.getTemplatePresentation().setIcon(AllIcons.General.Add);
    return action;
  }

  private abstract static class ExplorerAction extends DumbAwareAction {
    final HaxelibExplorerPanel panel;

    ExplorerAction(@NotNull HaxelibExplorerPanel panel, @NotNull Supplier<String> text) {
      super(text);
      this.panel = panel;
    }

    @Nullable
    VersionEntry selectedVersion() {
      return panel.selectedUserObject() instanceof VersionEntry entry ? entry : null;
    }

    @Nullable
    LibraryRow selectedLibrary() {
      return panel.selectedUserObject() instanceof LibraryRow row ? row : null;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    boolean confirmRemoval(@NotNull String target) {
      return Messages.showYesNoDialog(panel.getProject(),
                                      HaxeBundle.message("haxelib.explorer.action.remove.confirm", target),
                                      HaxeBundle.message("haxelib.explorer.action.remove.title"),
                                      Messages.getWarningIcon()) == Messages.YES;
    }

    /** Runs the mutation in the background; on success refreshes the explorer (dropping the library's stale info) and the v2 library sync. */
    void mutate(@NotNull String progressTitle, @NotNull String libraryName, @NotNull Supplier<@Nullable String> mutation) {
      Project project = panel.getProject();
      new Task.Backgroundable(project, progressTitle, true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          String failure = mutation.get();
          if (failure == null) {
            HaxeLibrarySync.sync(project, () -> { });
            ApplicationManager.getApplication().invokeLater(() -> panel.reloadAfterMutation(libraryName));
          }
          else {
            HaxeCommandNotifications.notify(project, progressTitle, failure, NotificationType.ERROR);
          }
        }
      }.queue();
    }
  }

  private static final class InstallAndSetCurrent extends ExplorerAction {
    private InstallAndSetCurrent(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.install.set.current"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      e.getPresentation().setEnabledAndVisible(entry != null && !entry.installed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      if (entry == null) return;
      // haxelib install SELECTS the installed version as a side effect;
      // passing no version to restore keeps that selection in place
      mutate(HaxeBundle.message("haxelib.explorer.action.install.progress", entry.library(), entry.version()),
             entry.library(),
             () -> HaxelibInstaller.install(panel.getProject(), entry.library(), entry.version(), null));
    }
  }

  private static final class InstallVersion extends ExplorerAction {
    private InstallVersion(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.install.version"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      boolean applicable = entry != null && !entry.installed();
      e.getPresentation().setEnabledAndVisible(applicable);
      if (applicable) {
        e.getPresentation().setText(
          HaxeBundle.message("haxelib.explorer.action.install.version.named", entry.library(), entry.version()));
      }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      LibraryRow row = panel.selectedLibraryRow();
      if (entry == null) return;
      String selected = row != null ? row.selectedVersion() : null;
      mutate(HaxeBundle.message("haxelib.explorer.action.install.progress", entry.library(), entry.version()),
             entry.library(),
             () -> HaxelibInstaller.install(panel.getProject(), entry.library(), entry.version(), selected));
    }
  }

  private static final class SetCurrent extends ExplorerAction {
    private SetCurrent(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.set.current"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      e.getPresentation().setEnabledAndVisible(entry != null && entry.installed() && !entry.current());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      LibraryRow row = panel.selectedLibraryRow();
      if (entry == null) return;
      // a dev pointer overrides the .current selection and haxelib set never
      // touches it - without clearing it the set would look ignored
      boolean devOverride = row != null && row.dev();
      mutate(HaxeBundle.message("haxelib.explorer.action.set.current.progress", entry.library(), entry.version()),
             entry.library(),
             () -> setCurrentClearingDev(entry, devOverride));
    }

    @Nullable
    private String setCurrentClearingDev(@NotNull VersionEntry entry, boolean devOverride) {
      String failure = HaxelibInstaller.setCurrent(panel.getProject(), entry.library(), entry.version());
      if (failure != null || !devOverride) return failure;
      return HaxelibInstaller.clearDev(panel.getProject(), entry.library());
    }
  }

  private static final class RemoveVersion extends ExplorerAction {
    private RemoveVersion(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.remove.version"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      // the dev pseudo-version is a pointer, not an installed directory -
      // Remove Development Directory handles it
      boolean removable = entry != null && entry.installed() && !HaxelibSemVer.DEV.equals(entry.version());
      e.getPresentation().setEnabledAndVisible(removable);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      LibraryRow row = panel.selectedLibraryRow();
      if (entry == null) return;
      String target = entry.library() + " " + entry.version();
      if (!confirmRemoval(target)) return;
      String fallback = newestOtherRelease(row, entry.version());
      mutate(HaxeBundle.message("haxelib.explorer.action.remove.progress", target),
             entry.library(),
             () -> removeSteppingOffCurrent(entry, fallback));
    }

    /**
     * haxelib refuses to remove the version its .current file names - even
     * when a dev pointer is what actually drives resolution. On that refusal
     * the newest other installed release is selected and the removal retried;
     * without one the removal cannot work (haxelib always keeps a current
     * version), reported with a pointer to Remove Library. Any other failure
     * (or a changed refusal wording in a future haxelib) surfaces as-is.
     */
    @Nullable
    private String removeSteppingOffCurrent(@NotNull VersionEntry entry, @Nullable String fallback) {
      Project project = panel.getProject();
      String failure = HaxelibInstaller.remove(project, entry.library(), entry.version());
      boolean currentRefusal = failure != null && failure.contains("Can't remove current version");
      if (!currentRefusal) return failure;
      if (fallback == null) {
        return HaxeBundle.message("haxelib.explorer.action.remove.version.last.release", entry.library());
      }
      String setFailure = HaxelibInstaller.setCurrent(project, entry.library(), fallback);
      if (setFailure != null) return setFailure;
      return HaxelibInstaller.remove(project, entry.library(), entry.version());
    }

    /** The newest OTHER installed release - what becomes current when the current version itself is removed. */
    @Nullable
    private static String newestOtherRelease(@Nullable LibraryRow row, @NotNull String removedVersion) {
      if (row == null) return null;
      return row.installedVersions().stream()
        .filter(version -> !HaxelibSemVer.isPseudoVersion(version) && !version.equals(removedVersion))
        .max(Comparator.comparing(RemoveVersion::releaseOrder))
        .orElse(null);
    }

    @NotNull
    private static HaxelibSemVer releaseOrder(@NotNull String version) {
      return HaxelibSemVer.create(version);
    }
  }

  private static final class InstallLatest extends ExplorerAction {
    private InstallLatest(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.install.latest"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      e.getPresentation().setEnabledAndVisible(row != null && !row.installed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      if (row == null) return;
      mutate(HaxeBundle.message("haxelib.explorer.action.install.latest.progress", row.name()),
             row.name(),
             () -> HaxelibInstaller.install(panel.getProject(), row.name(), null, null));
    }
  }

  private static final class SetDevDirectory extends ExplorerAction {
    private SetDevDirectory(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.set.dev"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      // registering a dev directory needs no installed release - haxelib
      // creates the repository entry, exactly how unpublished libs are used
      e.getPresentation().setEnabledAndVisible(selectedLibrary() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      if (row == null) return;
      FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleDir()
        .withTitle(HaxeBundle.message("haxelib.explorer.action.set.dev.chooser.title", row.name()));
      VirtualFile chosen = FileChooser.chooseFile(descriptor, panel.getProject(), currentDevDirectory(row));
      if (chosen == null) return;
      String directory = FileUtil.toSystemDependentName(chosen.getPath());
      mutate(HaxeBundle.message("haxelib.explorer.action.set.dev.progress", row.name()),
             row.name(),
             () -> HaxelibInstaller.setDev(panel.getProject(), row.name(), directory));
    }

    /** The chooser's starting point: the registered dev directory when one exists. */
    @Nullable
    private VirtualFile currentDevDirectory(@NotNull LibraryRow row) {
      String devPath = panel.devDirectoryOf(row);
      return devPath == null ? null : LocalFileSystem.getInstance().findFileByPath(devPath);
    }
  }

  private static final class AddLibrary extends ExplorerAction {
    private AddLibrary(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.add.library"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      HaxelibAddLibraryDialog dialog = new HaxelibAddLibraryDialog(panel.getProject());
      if (!dialog.showAndGet()) return;
      String name = dialog.getLibraryName();
      panel.revealAfterReload(name);
      if (dialog.isDevMethod()) {
        String directory = dialog.getDevDirectory();
        mutate(HaxeBundle.message("haxelib.explorer.action.set.dev.progress", name),
               name,
               () -> HaxelibInstaller.setDev(panel.getProject(), name, directory));
      }
      else {
        String url = dialog.getGitUrl();
        String ref = dialog.getGitRef();
        mutate(HaxeBundle.message("haxelib.explorer.action.install.git.progress", name),
               name,
               () -> HaxelibInstaller.installGit(panel.getProject(), name, url, ref));
      }
    }
  }

  private static final class InstallFromGitRepository extends ExplorerAction {
    private InstallFromGitRepository(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.install.git"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      // like a dev directory, a git install needs no installed release -
      // haxelib registers the library when unknown
      e.getPresentation().setEnabledAndVisible(selectedLibrary() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      if (row == null) return;
      HaxelibLocalDocs.GitCheckout existing = panel.gitCheckoutOf(row);
      HaxelibGitInstallDialog dialog = new HaxelibGitInstallDialog(panel.getProject(), row.name(),
                                                                  existing == null ? null : existing.remoteUrl(),
                                                                  existing == null ? null : existing.branch());
      if (!dialog.showAndGet()) return;
      String url = dialog.getUrl();
      String ref = dialog.getRef();
      mutate(HaxeBundle.message("haxelib.explorer.action.install.git.progress", row.name()),
             row.name(),
             () -> HaxelibInstaller.installGit(panel.getProject(), row.name(), url, ref));
    }
  }

  private static final class RemoveDevDirectory extends ExplorerAction {
    private RemoveDevDirectory(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.remove.dev"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(devLibraryName() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      String library = devLibraryName();
      if (library == null) return;
      mutate(HaxeBundle.message("haxelib.explorer.action.remove.dev.progress", library),
             library,
             () -> HaxelibInstaller.clearDev(panel.getProject(), library));
    }

    /** The selection's library when its dev pointer is set - the library node or any of its version nodes. */
    @Nullable
    private String devLibraryName() {
      LibraryRow row = panel.selectedLibraryRow();
      return row != null && row.dev() ? row.name() : null;
    }
  }

  private static final class RemoveLibrary extends ExplorerAction {
    private RemoveLibrary(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.remove.library"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      e.getPresentation().setEnabledAndVisible(row != null && row.installed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      LibraryRow row = selectedLibrary();
      if (row == null) return;
      if (!confirmRemoval(row.name())) return;
      mutate(HaxeBundle.message("haxelib.explorer.action.remove.progress", row.name()),
             row.name(),
             () -> HaxelibInstaller.remove(panel.getProject(), row.name(), null));
    }
  }
}
