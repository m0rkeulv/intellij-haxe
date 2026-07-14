package com.intellij.plugins.haxe.hxcpp;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.EvaluateResponse;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Watches/hover/Evaluate-dialog support: the expression is sent as written to
 * the hxcpp-debug-server, whose own interpreter evaluates it against the
 * frame (locals, members, statics, field/index access).
 */
final class HxcppDebuggerEvaluator extends XDebuggerEvaluator {
  private final HxcppDebugProcess process;
  private final int frameId;

  HxcppDebuggerEvaluator(HxcppDebugProcess process, int frameId) {
    this.process = process;
    this.frameId = frameId;
  }

  @Override
  public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback,
                       @Nullable XSourcePosition expressionPosition) {
    process.onRequestThread(() -> {
      EvaluateRequest request = new EvaluateRequest();
      EvaluateArguments arguments = new EvaluateArguments();
      arguments.setExpression(expression);
      arguments.setFrameId(frameId);
      arguments.setContext("watch");
      request.setArguments(arguments);

      Response response = process.sendRequest(request);
      if (response instanceof EvaluateResponse evaluated && response.isSuccess()) {
        Variable result = new Variable();
        result.setName(expression);
        result.setValue(evaluated.getBody().getResult());
        result.setType(evaluated.getBody().getType());
        result.setVariablesReference(evaluated.getBody().getVariablesReference());
        // a watch/hover result has no editable container (0); child expansion
        // still uses the result's own reference
        callback.evaluated(new HxcppValue(process, result, 0));
      } else {
        String message = response != null && response.getMessage() != null
                         ? response.getMessage() : "Cannot evaluate";
        callback.errorOccurred(message);
      }
    });
  }
}
