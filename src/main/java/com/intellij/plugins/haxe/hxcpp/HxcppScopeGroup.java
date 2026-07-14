package com.intellij.plugins.haxe.hxcpp;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueGroup;
import org.jetbrains.annotations.NotNull;

/**
 * A non-Locals DAP scope (e.g. "Members") shown as a collapsible group in the
 * Variables view; its variables are fetched on expand.
 */
final class HxcppScopeGroup extends XValueGroup {
  private final HxcppDebugProcess process;
  private final Scope scope;

  HxcppScopeGroup(HxcppDebugProcess process, Scope scope) {
    super(scope.getName() != null ? scope.getName() : "Scope");
    this.process = process;
    this.scope = scope;
  }

  @Override
  public void computeChildren(@NotNull XCompositeNode node) {
    process.onRequestThread(() -> {
      XValueChildrenList children = new XValueChildrenList();
      for (Variable variable : process.requestVariables(scope.getVariablesReference())) {
        children.add(new HxcppValue(process, variable, scope.getVariablesReference()));
      }
      node.addChildren(children, true);
    });
  }
}
