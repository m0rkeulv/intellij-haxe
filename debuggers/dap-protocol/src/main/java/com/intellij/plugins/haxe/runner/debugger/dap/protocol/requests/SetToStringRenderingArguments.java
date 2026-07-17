package com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests;

import lombok.Data;

/**
 * Arguments for the custom "intellij/setToStringRendering" request.
 */
@Data
public class SetToStringRenderingArguments {
  private boolean enabled;
}
