package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.CustomAction;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ActionNode;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.BuildFileRow;
import com.intellij.plugins.haxe.v2.toolwindow.ui.HaxeCustomActionDialog;
import org.jetbrains.annotations.NotNull;

/**
 * Tree context menu on a custom action row: edits its name, command, work directory and launch.
 */
public final class HaxeEditCustomActionAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeEditCustomActionAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.edit.action"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    BuildFileRow row = panel.getSelectedBuildFileRowAncestor();
    if (project == null || row == null || !(panel.getSelectedUserObject() instanceof ActionNode actionNode) || !actionNode.custom()) return;

    // edit the STORED values - the node's command is the ${target}-expanded display form
    CustomAction stored = HaxeCustomActionsStore.getInstance(project).getActions(actionNode.ownerId()).stream()
      .filter(action -> action.name().equals(actionNode.name()))
      .findFirst()
      .orElse(new CustomAction(actionNode.name(), actionNode.presentableCommand(), ""));
    HaxeCustomActionDialog dialog = HaxeCustomActionDialog.forAction(project, row.buildFile(), actionNode.launchOutput(), stored);
    if (dialog.showAndGet()) {
      CustomAction updated = dialog.getAction();
      HaxeCustomActionsStore.getInstance(project).updateAction(actionNode.ownerId(), actionNode.name(), updated);
      updateBuildSteps(project, actionNode, updated);
      panel.refreshTree();
    }
  }

  /** Build steps naming the action follow its new name; an emptied command drops them, since they would fail. */
  private static void updateBuildSteps(@NotNull Project project, @NotNull ActionNode before, @NotNull CustomAction updated) {
    String stepName = updated.command().isBlank() ? null : updated.name();
    if (!before.name().equals(stepName)) {
      HaxeProgramLaunches.retargetBuildSteps(project, before.ownerId(), before.name(), stepName);
    }
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
