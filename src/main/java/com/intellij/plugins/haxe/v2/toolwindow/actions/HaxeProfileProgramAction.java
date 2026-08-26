package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilerExecutorSupport;
import com.intellij.plugins.haxe.v2.runconfig.HaxeProgramLaunches;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.ProgramNode;
import org.jetbrains.annotations.NotNull;

/**
 * Profile on the "compile &amp; run" tree row: launches the build file's
 * program through its lane's IU profiler entry (compile attached as a
 * before-launch step, with the profiler's compile additions where the lane
 * needs them). Hidden on IDEs without the profiler module and on targets
 * without a profiler lane.
 */
public final class HaxeProfileProgramAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeProfileProgramAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.profile.action"), null, AllIcons.Actions.Profile);
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof ProgramNode programNode) {
      panel.executeProgramWithProfiler(programNode);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean applicable = panel.getSelectedUserObject() instanceof ProgramNode programNode
                         && HaxeProfilerExecutorSupport.getInstance() != null
                         && HaxeProgramLaunches.supportsProgramProfiling(programNode.target());
    e.getPresentation().setEnabledAndVisible(applicable);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
