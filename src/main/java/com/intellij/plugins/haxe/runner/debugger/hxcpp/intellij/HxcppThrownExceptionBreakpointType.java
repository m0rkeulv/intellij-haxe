package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HXCPP Thrown Exceptions" breakpoint: stops where a {@code
 * haxe.Exception} (or any subclass) is CONSTRUCTED — normally the throw
 * expression — even when the throw will be caught. Drives the "thrown"
 * filter of {@code setExceptionBreakpoints}; the server implements it as a
 * class-function breakpoint on {@code haxe.Exception.new}, which every
 * subclass constructor runs through via {@code super()}. Raw-value throws
 * ({@code throw "str"}, enums, ints) never construct an Exception and are
 * not visible to this filter. OFF by default: exception-heavy code would
 * stop constantly.
 */
public class HxcppThrownExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HxcppThrownExceptionBreakpointType() {
    super("hxcpp-thrown-exception", "HXCPP Thrown Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any thrown haxe.Exception (including caught ones)";
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
    breakpoint.setEnabled(false); // off by default; the user opts in
    return breakpoint;
  }
}
