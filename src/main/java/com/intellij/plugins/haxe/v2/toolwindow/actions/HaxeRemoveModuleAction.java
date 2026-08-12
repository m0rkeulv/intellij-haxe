package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeModuleWorkspace;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ModuleNode;
import org.jetbrains.annotations.NotNull;

/**
 * Toolbar action on a selected module row: removes the module from the project
 * model after confirmation. Files on disk are untouched - the folder simply
 * folds back into the surrounding module.
 */
public final class HaxeRemoveModuleAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeRemoveModuleAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.remove.module"),
          HaxeBundle.message("haxe.toolwindow.remove.module.description"),
          AllIcons.General.Remove);
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null || !(panel.getSelectedUserObject() instanceof ModuleNode moduleNode)) return;

    int answer = Messages.showYesNoDialog(
      project,
      HaxeBundle.message("haxe.toolwindow.remove.module.confirm", moduleNode.name()),
      HaxeBundle.message("haxe.toolwindow.remove.module"),
      Messages.getWarningIcon());
    if (answer != Messages.YES) return;

    HaxeModuleWorkspace.getInstance(project).removeModuleAsync(moduleNode.name());
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(panel.getSelectedUserObject() instanceof ModuleNode);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
