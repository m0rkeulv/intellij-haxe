package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ActionNode;
import org.jetbrains.annotations.NotNull;

/**
 * Tree context menu on a custom action row: removes it after a confirmation,
 * since the entry sits beside Edit and Duplicate.
 */
public final class HaxeRemoveCustomActionAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeRemoveCustomActionAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.remove.action"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null || !(panel.getSelectedUserObject() instanceof ActionNode actionNode) || !actionNode.custom()) return;
    int answer = Messages.showYesNoDialog(project,
                                          HaxeBundle.message("haxe.toolwindow.remove.action.confirm", actionNode.name()),
                                          HaxeBundle.message("haxe.toolwindow.remove.action"),
                                          Messages.getQuestionIcon());
    if (answer != Messages.YES) return;
    HaxeCustomActionsStore.getInstance(project).removeAction(actionNode.ownerId(), actionNode.name());
    panel.refreshTree();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean custom = panel.getSelectedUserObject() instanceof ActionNode actionNode && actionNode.custom();
    e.getPresentation().setEnabledAndVisible(custom);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
