package com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests;

import lombok.Data;

/** Arguments of {@link SetExpressionSteppingRequest}. */
@Data
public class SetExpressionSteppingArguments {
  private boolean enabled;
}
