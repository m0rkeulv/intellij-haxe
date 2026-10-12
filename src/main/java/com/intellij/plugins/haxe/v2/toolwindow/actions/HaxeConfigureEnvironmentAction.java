package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.EnvironmentNode;
import com.intellij.plugins.haxe.v2.toolwindow.ui.HaxeEnvironmentDialog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Tree context menu on any environment row: opens the Configure Environment dialog (SDK selector + defines table). */
public final class HaxeConfigureEnvironmentAction extends HaxeSelectedNodeAction<EnvironmentNode> {

  public HaxeConfigureEnvironmentAction(@NotNull HaxeToolWindowPanel panel) {
    super(panel, EnvironmentNode.class, HaxeBundle.message("haxe.toolwindow.configure.environment"));
  }

  @Override
  protected @Nullable Object selection() {
    return panel.getSelectedEnvironmentNode();
  }

  @Override
  protected void perform(@NotNull Project project, @NotNull EnvironmentNode environment) {
    HaxeEnvironmentDialog dialog = new HaxeEnvironmentDialog(project, environment.containerId(),
                                                             environment.displayName(),
                                                             environment.activeBuildFileDefines());
    dialog.show();
  }
}
