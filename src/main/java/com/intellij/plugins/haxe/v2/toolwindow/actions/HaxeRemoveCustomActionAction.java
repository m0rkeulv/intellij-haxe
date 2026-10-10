package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeActionReferences;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ActionNode;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/**
 * Tree context menu on a custom action row: removes it, after a confirmation
 * since the entry sits beside Edit and Duplicate, and drops the "Run Haxe
 * action" build steps and the run-before references that named it.
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
    HaxeCustomActionsStore store = HaxeCustomActionsStore.getInstance(project);
    List<String> dependents = store.dependents(actionNode.ownerId(), actionNode.name()).stream()
      .map(reference -> HaxeActionReferences.label(project, actionNode.ownerId(), reference))
      .toList();
    String question = confirmation(dependents, actionNode.name());
    int answer = Messages.showYesNoDialog(project, question, HaxeBundle.message("haxe.toolwindow.remove.action"), Messages.getQuestionIcon());
    if (answer != Messages.YES) return;
    store.removeAction(actionNode.ownerId(), actionNode.name());
    store.dropReferences(actionNode.ownerId(), actionNode.name());
    HaxeProgramLaunches.retargetBuildSteps(project, actionNode.ownerId(), actionNode.name(), null);
    panel.refreshTree();
  }

  /** The question, naming the actions that run this one first: their reference goes with it. */
  @NotNull
  private static String confirmation(@NotNull List<String> dependents, @NotNull String name) {
    if (dependents.isEmpty()) return HaxeBundle.message("haxe.toolwindow.remove.action.confirm", name);
    return HaxeBundle.message("haxe.toolwindow.remove.action.confirm.used", name, String.join(", ", dependents));
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
