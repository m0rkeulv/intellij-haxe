package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.runconfig.HaxeDebugSupport;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ProgramNode;
import org.jetbrains.annotations.NotNull;

/**
 * Run / Debug on the "compile &amp; run" tree row: launches the build file's
 * program through the target's visible run configuration (compile attached as
 * a before-launch step). The plain compile action row is untouched — compiling
 * and running the output are separate concepts.
 */
public final class HaxeRunProgramAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;
  private final boolean debug;

  public HaxeRunProgramAction(@NotNull HaxeToolWindowPanel panel, boolean debug) {
    super(HaxeBundle.message(debug ? "haxe.toolwindow.debug.action" : "haxe.toolwindow.run.action"),
          null,
          debug ? AllIcons.Actions.StartDebugger : AllIcons.Actions.Execute);
    this.panel = panel;
    this.debug = debug;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof ProgramNode programNode) {
      panel.executeProgram(programNode, debug);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    // the Debug variant shows only for targets a debugger lane exists for -
    // neko, for one, runs but cannot be debugged
    boolean applicable = panel.getSelectedUserObject() instanceof ProgramNode programNode
                         && (!debug || HaxeDebugSupport.supportsProgramDebug(programNode.target()));
    e.getPresentation().setEnabledAndVisible(applicable);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
