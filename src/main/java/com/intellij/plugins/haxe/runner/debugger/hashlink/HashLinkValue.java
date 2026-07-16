package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.icons.AllIcons;
import com.intellij.plugins.haxe.runner.debugger.HaxeVariableSourceNavigator;
import com.intellij.plugins.haxe.runner.debugger.dap.EvaluationPath;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.VariableKind;
import javax.swing.Icon;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XNavigatable;
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
 * A variable from the adapter: value + type as reported, expandable when the
 * adapter handed out a variablesReference (objects, arrays, enums, ...).
 * Editable via {@link XValueModifier} when it belongs to a container reference
 * (a Locals scope, an object, an array), through the adapter's setVariable.
 *
 * Each value carries the access-path EXPRESSION from its frame's local down to
 * itself ("this.someObject.someArray[0].myVar"), built as the tree expands.
 * {@link #calculateEvaluationExpression()} hands it to the platform, which is
 * what pre-fills the Evaluate Expression dialog from a selected tree node —
 * the same convenience the Java debugger offers.
 */
final class HashLinkValue extends XNamedValue {
  private final HashLinkDebugProcess process;
  private final Variable variable;
  private final int containerReference;
  private final @Nullable String evaluationPath;

  /**
   * @param containerReference the reference this variable is a child of (0 = not editable).
   * @param evaluationPath this node's access-path expression, or null when it is
   *                       not expressible (see {@link EvaluationPath}).
   */
  HashLinkValue(HashLinkDebugProcess process, Variable variable, int containerReference, @Nullable String evaluationPath) {
    super(variable.getName() != null ? variable.getName() : "?");
    this.process = process;
    this.variable = variable;
    this.containerReference = containerReference;
    this.evaluationPath = evaluationPath;
  }

  @Override
  public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
    boolean expandable = variable.getVariablesReference() > 0;
    String value = variable.getValue() != null ? variable.getValue() : "";
    node.setPresentation(iconFor(variable.getKind()),
                         new XRegularValuePresentation(value, variable.getType()),
                         expandable);
  }

  /** Maps the adapter's classification to a node icon; a plain value otherwise. */
  private static Icon iconFor(VariableKind kind) {
    return switch (kind) {
      case ARGUMENT -> AllIcons.Nodes.Parameter;
      case LOCAL -> AllIcons.Nodes.Variable;
      case STATIC -> AllIcons.Nodes.Static;
      case FIELD -> AllIcons.Nodes.Field;
      case UNSPECIFIED -> AllIcons.Debugger.Value;
    };
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
        children.add(new HashLinkValue(process, child, reference, EvaluationPath.child(evaluationPath, child.getName())));
      }
      node.addChildren(children, true);
    });
  }

  // Pre-fills the Evaluate Expression dialog when this node is selected; no
  // prefill (empty dialog) for a node whose path is not expressible.
  @Override
  public @NotNull Promise<XExpression> calculateEvaluationExpression() {
    return Promises.resolvedPromise(evaluationPath != null ? XExpressionImpl.fromText(evaluationPath) : null);
  }

  @Override
  public boolean canNavigateToSource() {
    return evaluationPath != null;
  }

  // "Jump to Source": resolve the access path through the Haxe resolver and
  // land on the member's declaration (see HaxeVariableSourceNavigator).
  @Override
  public void computeSourcePosition(@NotNull XNavigatable navigatable) {
    HaxeVariableSourceNavigator.navigate(process.getSession(), evaluationPath, navigatable);
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
            process.refreshRegistersTab();
            callback.valueModified();
          } catch (RuntimeException e) {
            callback.errorOccurred(e.getMessage() != null ? e.getMessage() : "Could not set value");
          }
        });
      }
    };
  }
}
