package com.intellij.plugins.haxe.ide.toolWindow.haxelib;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.toolWindow.haxelib.HaxelibExplorerPanel.LibraryRow;
import com.intellij.plugins.haxe.ide.toolWindow.haxelib.HaxelibExplorerPanel.VersionEntry;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCommandNotifications;
import com.intellij.plugins.haxe.v2.buildtools.HaxeLibrarySync;
import com.intellij.plugins.haxe.v2.buildtools.HaxelibInstaller;
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
    return group;
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

    /** Runs the mutation in the background; on success refreshes the explorer and the v2 library sync. */
    void mutate(@NotNull String progressTitle, @NotNull Supplier<@Nullable String> mutation) {
      Project project = panel.getProject();
      new Task.Backgroundable(project, progressTitle, true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          String failure = mutation.get();
          if (failure == null) {
            HaxeLibrarySync.sync(project, () -> { });
            ApplicationManager.getApplication().invokeLater(panel::reloadAfterMutation);
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
      if (entry == null) return;
      mutate(HaxeBundle.message("haxelib.explorer.action.set.current.progress", entry.library(), entry.version()),
             () -> HaxelibInstaller.setCurrent(panel.getProject(), entry.library(), entry.version()));
    }
  }

  private static final class RemoveVersion extends ExplorerAction {
    private RemoveVersion(@NotNull HaxelibExplorerPanel panel) {
      super(panel, () -> HaxeBundle.message("haxelib.explorer.action.remove.version"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      e.getPresentation().setEnabledAndVisible(entry != null && entry.installed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      VersionEntry entry = selectedVersion();
      if (entry == null) return;
      String target = entry.library() + " " + entry.version();
      if (!confirmRemoval(target)) return;
      mutate(HaxeBundle.message("haxelib.explorer.action.remove.progress", target),
             () -> HaxelibInstaller.remove(panel.getProject(), entry.library(), entry.version()));
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
             () -> HaxelibInstaller.install(panel.getProject(), row.name(), null, null));
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
             () -> HaxelibInstaller.remove(panel.getProject(), row.name(), null));
    }
  }
}
