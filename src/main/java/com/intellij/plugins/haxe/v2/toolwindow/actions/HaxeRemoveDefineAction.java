package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowEditors;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.EnvDefineNode;
import org.jetbrains.annotations.NotNull;

/** Tree context menu on an environment define: removes it after the same confirmation the Delete key shows. */
public final class HaxeRemoveDefineAction extends HaxeSelectedNodeAction<EnvDefineNode> {

  public HaxeRemoveDefineAction(@NotNull HaxeToolWindowPanel panel) {
    super(panel, EnvDefineNode.class, HaxeBundle.message("haxe.toolwindow.remove.define"));
  }

  @Override
  protected void perform(@NotNull Project project, @NotNull EnvDefineNode define) {
    HaxeToolWindowEditors.confirmAndRemoveDefine(project, define);
  }
}
