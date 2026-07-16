package com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests;

import lombok.Data;

/**
 * Arguments for the "exceptionInfo" request.
 */
@Data
public class ExceptionInfoArguments {
  private int threadId;
}
