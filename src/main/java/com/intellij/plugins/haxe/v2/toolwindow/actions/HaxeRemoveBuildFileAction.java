package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeBuildFilesStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.BuildFileRow;
import org.jetbrains.annotations.NotNull;

/**
 * Tree context menu on a build file row: removes a manually added file, or hides
 * an auto-detected one (re-adding it via Add Build File un-hides it).
 */
public final class HaxeRemoveBuildFileAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeRemoveBuildFileAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.remove.build.file"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null && panel.getSelectedUserObject() instanceof BuildFileRow row) {
      HaxeBuildFilesStore.getInstance(project).removeFile(row.containerId(), row.buildFile().file().getPath());
      panel.refreshTree();
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof BuildFileRow row) {
      e.getPresentation().setEnabledAndVisible(true);
      e.getPresentation().setText(HaxeBundle.message(row.manual() ? "haxe.toolwindow.remove.build.file"
                                                                  : "haxe.toolwindow.hide.build.file"));
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
