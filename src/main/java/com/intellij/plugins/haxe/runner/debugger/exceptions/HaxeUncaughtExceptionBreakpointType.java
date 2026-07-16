package com.intellij.plugins.haxe.runner.debugger.exceptions;

import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Haxe Uncaught Exceptions": stop at a throw that no live {@code try/catch}
 * will handle, before unwinding — shared by the HashLink and HXCPP debuggers
 * (each maps it onto its own wire filter: HashLink "uncaught", HXCPP
 * "uncaught"). ON by default: an uncaught throw is about to terminate the
 * program, which is exactly what a debugging user wants to see.
 */
public class HaxeUncaughtExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HaxeUncaughtExceptionBreakpointType() {
    super("haxe-uncaught-exception", "Haxe Uncaught Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any uncaught exception";
  }

  // the lightning-bolt exception icons, like the Java debugger's
  @Override
  public @NotNull Icon getEnabledIcon() {
    return AllIcons.Debugger.Db_exception_breakpoint;
  }

  @Override
  public @NotNull Icon getDisabledIcon() {
    return AllIcons.Debugger.Db_disabled_exception_breakpoint;
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
