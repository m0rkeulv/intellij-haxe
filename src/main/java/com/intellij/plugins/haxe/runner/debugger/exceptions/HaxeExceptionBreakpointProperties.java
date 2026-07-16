package com.intellij.plugins.haxe.runner.debugger.exceptions;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent state for a per-class exception breakpoint: the exception class
 * the debugger should stop on (FQN or simple name). Matched against the
 * thrown value's runtime class and its superclasses on the debugger side.
 */
public class HaxeExceptionBreakpointProperties
  extends XBreakpointProperties<HaxeExceptionBreakpointProperties> {

  @Attribute("className")
  public String className;

  @Override
  public @Nullable HaxeExceptionBreakpointProperties getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull HaxeExceptionBreakpointProperties state) {
    className = state.className;
  }
}
