package com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests;

import lombok.Data;

/**
 * Arguments for the custom "intellij/stepIntoFunction" request: the callee to
 * enter, named the way hxcpp generated code names its stack frames — dotted
 * class path ("my.pack.Target") plus bare function name ("update").
 */
@Data
public class StepIntoFunctionArguments {
  private int threadId;
  private String className;
  private String functionName;
}
