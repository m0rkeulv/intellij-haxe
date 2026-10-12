package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.libraries.HaxelibInstallTask;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.LibraryNode;
import org.jetbrains.annotations.NotNull;

/// Tree context menu on a missing library: runs `haxelib install <name> [version] --always`
/// in the background ([HaxelibInstallTask]) and refreshes the tree on success.
public final class HaxeInstallLibraryAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeInstallLibraryAction(@NotNull HaxeToolWindowPanel panel) {
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null && panel.getSelectedUserObject() instanceof LibraryNode library && !library.installed()) {
      HaxelibInstallTask.queue(project, library.name(), library.version(), library.resolvedVersion(), null);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof LibraryNode library && !library.installed()) {
      e.getPresentation().setEnabledAndVisible(true);
      e.getPresentation().setText(HaxeBundle.message("haxe.toolwindow.install.library", library.name()));
    }
    else {
      e.getPresentation().setEnabledAndVisible(false);
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
