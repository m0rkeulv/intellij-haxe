package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One stack frame. Children are the frame's DAP scopes: the first scope's
 * variables (Locals) inline, any further scopes (e.g. "Members", "Statics")
 * as lazy groups.
 */
final class HxcppStackFrame extends XStackFrame {
  private static final Logger LOG = Logger.getInstance(HxcppStackFrame.class);

  private final HxcppDebugProcess process;
  private final StackFrame frame;

  HxcppStackFrame(HxcppDebugProcess process, StackFrame frame) {
    this.process = process;
    this.frame = frame;
  }

  int frameId() {
    return frame.getId();
  }

  /**
   * Identifies "the same frame" across steps so the platform restores the
   * previously expanded variable nodes and highlights changed values, instead
   * of collapsing the tree on every stop. Keyed by the function plus its
   * source path (independent of the current line, so a step within a method
   * restores). Null name → no stable identity, let the platform rebuild.
   */
  @Override
  public @Nullable Object getEqualityObject() {
    String name = frame.getName();
    if (name == null) {
      return null;
    }
    String path = frame.getSource() != null ? frame.getSource().getPath() : null;
    return path != null ? name + "@" + path : name;
  }

  @Override
  public @Nullable XDebuggerEvaluator getEvaluator() {
    return new HxcppDebuggerEvaluator(process, frame.getId());
  }

  @Override
  public @Nullable XSourcePosition getSourcePosition() {
    String path = frame.getSource() != null ? frame.getSource().getPath() : null;
    try {
      return HxcppSourceResolver.resolve(process.getSession().getProject(), path, frame.getLine());
    } catch (RuntimeException e) {
      // one unresolvable frame must never wedge the whole Frames panel
      LOG.warn("Cannot resolve source for frame '" + frame.getName() + "' (" + path + ")", e);
      return null;
    }
  }

  @Override
  public void customizePresentation(@NotNull ColoredTextContainer component) {
    component.append(frame.getName() != null ? frame.getName() : "<unknown>", SimpleTextAttributes.REGULAR_ATTRIBUTES);
    String file = frame.getSource() != null ? frame.getSource().getName() : null;
    String location = file != null ? " (" + file + ":" + frame.getLine() + ")" : " (no source)";
    component.append(location, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    component.setIcon(AllIcons.Debugger.Frame);
  }

  @Override
  public void computeChildren(XCompositeNode node) {
    process.onRequestThread(() -> {
      List<Scope> scopes = process.requestScopes(frame.getId());
      XValueChildrenList children = new XValueChildrenList();
      boolean first = true;
      for (Scope scope : scopes) {
        if (first) {
          // the Locals scope: variables straight into the frame node
          for (Variable variable : process.requestVariables(scope.getVariablesReference())) {
            children.add(new HxcppValue(process, variable, scope.getVariablesReference(), null));
          }
          first = false;
        } else {
          children.addTopGroup(new HxcppScopeGroup(process, scope));
        }
      }
      node.addChildren(children, true);
    });
  }
}
