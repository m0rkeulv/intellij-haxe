package com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests;

import lombok.Data;

/**
 * Arguments for the "stepInTargets" request.
 */
@Data
public class StepInTargetsArguments {
  private int frameId;
}
