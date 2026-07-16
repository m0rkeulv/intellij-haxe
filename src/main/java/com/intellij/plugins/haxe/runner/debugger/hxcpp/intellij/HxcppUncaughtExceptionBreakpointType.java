package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HXCPP Uncaught Exceptions" breakpoint: stops where a value is thrown
 * that NO enclosing {@code try/catch} can catch — hxcpp walks the live frames'
 * declared catch types at the throw, so this is real typed uncaught-detection,
 * and the stop lands at the throw site before unwinding (locals inspectable).
 * Drives the "uncaught" filter of {@code setExceptionBreakpoints}. ENABLED by
 * default, matching the debug server's own default: an uncaught throw is about
 * to terminate the program, which is exactly what a debugging user wants to see.
 */
public class HxcppUncaughtExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HxcppUncaughtExceptionBreakpointType() {
    super("hxcpp-uncaught-exception", "HXCPP Uncaught Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any uncaught HXCPP exception";
  }

  @Override
  public @Nullable XBreakpointProperties createProperties() {
    return null;
  }

  // only the single toggle — no user-added variants
  @Override
  public boolean isAddBreakpointButtonVisible() {
    return false;
  }

  @Override
  public XBreakpoint<XBreakpointProperties> createDefaultBreakpoint(@NotNull XBreakpointCreator<XBreakpointProperties> creator) {
    XBreakpoint<XBreakpointProperties> breakpoint = creator.createBreakpoint(null);
    breakpoint.setEnabled(true); // on by default: an unstopped uncaught throw just kills the program
    return breakpoint;
  }
}
