package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeModuleWorkspace;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildFilesStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.BuildFileRow;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ModuleNode;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar remove, acting on whatever row is selected: a module is removed
 * from the project model after confirmation (files on disk untouched - the
 * folder folds back into the surrounding module); a manually added build
 * file is removed; an auto-detected one is hidden (re-adding it via Add
 * Build File un-hides it).
 */
public final class HaxeRemoveNodeAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeRemoveNodeAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.remove.module"),
          HaxeBundle.message("haxe.toolwindow.remove.module.description"),
          AllIcons.General.Remove);
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    switch (panel.getSelectedUserObject()) {
      case ModuleNode moduleNode -> panel.confirmAndRemoveModule(moduleNode);
      case BuildFileRow row -> {
        HaxeBuildFilesStore.getInstance(project).removeFile(row.containerId(), row.buildFile().file().getPath());
        panel.refreshTree();
      }
      case null, default -> { }
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    switch (panel.getSelectedUserObject()) {
      case ModuleNode ignored -> {
        e.getPresentation().setEnabled(true);
        e.getPresentation().setText(HaxeBundle.message("haxe.toolwindow.remove.module"));
      }
      case BuildFileRow row -> {
        e.getPresentation().setEnabled(true);
        e.getPresentation().setText(HaxeBundle.message(row.manual() ? "haxe.toolwindow.remove.build.file"
                                                                    : "haxe.toolwindow.hide.build.file"));
      }
      case null, default -> {
        e.getPresentation().setEnabled(false);
        e.getPresentation().setText(HaxeBundle.message("haxe.toolwindow.remove.module"));
      }
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
