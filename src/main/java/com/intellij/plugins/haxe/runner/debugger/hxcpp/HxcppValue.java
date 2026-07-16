package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.icons.AllIcons;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

/**
 * A variable from the debugger: value + type as reported, expandable when the
 * server handed out a variablesReference (objects, arrays, maps, ...).
 * Editable via {@link XValueModifier} when it belongs to a container
 * reference, through the server's setVariable.
 *
 * Each value carries the access-path EXPRESSION from its frame's local down to
 * itself ("this.someObject.someArray[0].myVar"), built as the tree expands.
 * {@link #calculateEvaluationExpression()} hands it to the platform, which is
 * what pre-fills the Evaluate Expression dialog from a selected tree node —
 * the same convenience the Java debugger offers.
 */
final class HxcppValue extends XNamedValue {
  private final HxcppDebugProcess process;
  private final Variable variable;
  private final int containerReference;
  private final String evaluationPath;

  /**
   * @param containerReference the reference this variable is a child of (0 = not editable).
   * @param parentPath the parent's access path, or null for a frame local (path = own name).
   */
  HxcppValue(HxcppDebugProcess process, Variable variable, int containerReference, @Nullable String parentPath) {
    super(variable.getName() != null ? variable.getName() : "?");
    this.process = process;
    this.variable = variable;
    this.containerReference = containerReference;
    this.evaluationPath = childPath(parentPath, getName());
  }

  // "[0]" children append without a dot; named fields join with one.
  private static String childPath(@Nullable String parentPath, String name) {
    if (parentPath == null || parentPath.isEmpty()) {
      return name;
    }
    return name.startsWith("[") ? parentPath + name : parentPath + "." + name;
  }

  @Override
  public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
    boolean expandable = variable.getVariablesReference() > 0;
    String value = variable.getValue() != null ? variable.getValue() : "";
    node.setPresentation(AllIcons.Debugger.Value,
                         new XRegularValuePresentation(value, variable.getType()),
                         expandable);
  }

  @Override
  public void computeChildren(@NotNull XCompositeNode node) {
    int reference = variable.getVariablesReference();
    if (reference <= 0) {
      node.addChildren(XValueChildrenList.EMPTY, true);
      return;
    }
    process.onRequestThread(() -> {
      XValueChildrenList children = new XValueChildrenList();
      for (Variable child : process.requestVariables(reference)) {
        children.add(new HxcppValue(process, child, reference, evaluationPath));
      }
      node.addChildren(children, true);
    });
  }

  // Pre-fills the Evaluate Expression dialog when this node is selected.
  @Override
  public @NotNull Promise<XExpression> calculateEvaluationExpression() {
    return Promises.resolvedPromise(XExpressionImpl.fromText(evaluationPath));
  }

  @Override
  public @Nullable XValueModifier getModifier() {
    if (containerReference <= 0) {
      return null; // no container to set this value against
    }
    return new XValueModifier() {
      @Override
      public @Nullable String getInitialValueEditorText() {
        return variable.getValue();
      }

      @Override
      public void setValue(@NotNull XExpression expression, @NotNull XModificationCallback callback) {
        String text = expression.getExpression();
        process.onRequestThread(() -> {
          try {
            String newValue = process.requestSetVariable(containerReference, variable.getName(), text);
            // the node re-presents THIS instance after the edit: update the
            // cached variable or the view keeps showing the old value
            variable.setValue(newValue);
            callback.valueModified();
          } catch (RuntimeException e) {
            callback.errorOccurred(e.getMessage() != null ? e.getMessage() : "Could not set value");
          }
        });
      }
    };
  }
}
