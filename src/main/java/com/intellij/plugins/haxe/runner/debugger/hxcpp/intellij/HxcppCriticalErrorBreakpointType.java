package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HXCPP Critical Errors" breakpoint: stops on runtime-raised critical
 * errors — null access, GC pointer errors — which the hxcpp runtime reports to
 * an attached debugger UNCONDITIONALLY (even inside a {@code try/catch} that
 * would have caught the resulting throw in an undebugged run). Drives the
 * "critical" filter of {@code setExceptionBreakpoints}. ENABLED by default,
 * matching the debug server's own default; note that resuming past a critical
 * error usually re-faults or crashes — the stop is for inspection, not recovery.
 */
public class HxcppCriticalErrorBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HxcppCriticalErrorBreakpointType() {
    super("hxcpp-critical-error", "HXCPP Critical Errors");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any HXCPP critical error (null access, GC errors, ...)";
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
    breakpoint.setEnabled(true); // on by default: an unstopped critical error just kills the program
    return breakpoint;
  }
}
