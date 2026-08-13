package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ModuleNode;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ProjectNode;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.TestRunNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tree context menu: runs the selection's unit tests - directly on the "Run Unit
 * Tests" row, or on a container row through its marked (or convention-suggested)
 * tests build file. Disabled with a hint while the container has none.
 */
public final class HaxeRunUnitTestsAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeRunUnitTestsAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.node.run.unit.tests"), null, AllIcons.RunConfigurations.TestState.Run);
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    String buildFilePath = resolveTestsPath(panel.getSelectedUserObject());
    if (buildFilePath != null) {
      panel.runUnitTests(buildFilePath);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Object selection = panel.getSelectedUserObject();
    if (selection instanceof TestRunNode) {
      e.getPresentation().setEnabledAndVisible(true);
      return;
    }
    if (!(selection instanceof ModuleNode) && !(selection instanceof ProjectNode)) {
      e.getPresentation().setEnabledAndVisible(false);
      return;
    }
    boolean hasTestsFile = resolveTestsPath(selection) != null;
    e.getPresentation().setVisible(true);
    e.getPresentation().setEnabled(hasTestsFile);
    if (!hasTestsFile) {
      e.getPresentation().setDescription(HaxeBundle.message("haxe.toolwindow.run.unit.tests.no.tests.file"));
    }
  }

  /** The selection's tests build file: the row's own file, or the container's marked/suggested one from the last scan. */
  @Nullable
  private String resolveTestsPath(@Nullable Object selection) {
    return switch (selection) {
      case TestRunNode node -> node.buildFilePath();
      case ModuleNode module -> panel.testsPathFor(module.name());
      case ProjectNode ignored -> {
        String containerId = panel.getProjectRootContainerId();
        yield containerId == null ? null : panel.testsPathFor(containerId);
      }
      case null, default -> null;
    };
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
