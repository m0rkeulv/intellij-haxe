package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.EnvSdkNode;
import org.jetbrains.annotations.NotNull;

/**
 * Tree context menu on the environment SDK row: opens the SDK dropdown.
 */
public final class HaxeSelectEnvironmentSdkAction extends DumbAwareAction {

  private final HaxeToolWindowPanel panel;

  public HaxeSelectEnvironmentSdkAction(@NotNull HaxeToolWindowPanel panel) {
    super(HaxeBundle.message("haxe.toolwindow.select.sdk.title"));
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof EnvSdkNode sdkNode) {
      panel.showEnvironmentSdkPopup(sdkNode, panel.getSelectionPopupPoint());
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(panel.getSelectedUserObject() instanceof EnvSdkNode);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
