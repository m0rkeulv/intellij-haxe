package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeActionReferences;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.actions.HaxeCustomActionRemoval.Decisions;
import com.intellij.plugins.haxe.v2.toolwindow.actions.HaxeCustomActionRemoval.References;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ActionNode;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tree context menu on a custom action row: removes it after a confirmation
 * (the entry sits beside Edit and Duplicate); when other actions run it first,
 * a second question decides whether their references go with it or stay as
 * missing ({@link HaxeCustomActionRemoval}). The "Run Haxe action" build steps
 * that named it are dropped either way.
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
    Decisions decisions = new DialogDecisions(project, actionNode.ownerId());
    if (!HaxeCustomActionRemoval.remove(store, actionNode.ownerId(), actionNode.name(), decisions)) return;
    HaxeProgramLaunches.retargetBuildSteps(project, actionNode.ownerId(), actionNode.name(), null);
    panel.refreshTree();
  }

  /** The two questions as a Yes/No and a Yes/No/Cancel dialog; No on the second keeps the references. */
  private record DialogDecisions(@NotNull Project project, @NotNull String ownerId) implements Decisions {

    @Override
    public boolean confirmRemoval(@NotNull String name) {
      String question = HaxeBundle.message("haxe.toolwindow.remove.action.confirm", name);
      return Messages.showYesNoDialog(project, question, title(), Messages.getQuestionIcon()) == Messages.YES;
    }

    @Override
    public @NotNull References references(@NotNull String name, @NotNull List<ActionReference> users) {
      String userLabels = users.stream()
        .map(reference -> HaxeActionReferences.label(project, ownerId, reference))
        .collect(Collectors.joining(", "));
      String question = HaxeBundle.message("haxe.toolwindow.remove.action.references", name, userLabels);
      return switch (Messages.showYesNoCancelDialog(project, question, title(), Messages.getQuestionIcon())) {
        case Messages.YES -> References.REMOVE;
        case Messages.NO -> References.KEEP;
        default -> References.CANCEL;
      };
    }

    private static String title() {
      return HaxeBundle.message("haxe.toolwindow.remove.action");
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
