package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.EnvCompileCommandNode;
import org.jetbrains.annotations.NotNull;

/**
 * Tree context menu on the Compile command row: opens its configuration dialog.
 */
public final class HaxeConfigureCompileCommandAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeConfigureCompileCommandAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.configure.compile.command"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof EnvCompileCommandNode buildCommand) {
      panel.configureCompileCommand(buildCommand);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(panel.getSelectedUserObject() instanceof EnvCompileCommandNode);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
