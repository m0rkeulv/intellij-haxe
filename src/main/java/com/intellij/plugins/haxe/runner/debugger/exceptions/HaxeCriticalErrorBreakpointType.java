package com.intellij.plugins.haxe.runner.debugger.exceptions;

import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Haxe Critical Errors": runtime-raised errors that never execute a user
 * {@code throw} — null access, out-of-bounds, invalid casts, GC errors —
 * shared by the HashLink and HXCPP debuggers (HashLink maps it to its "vm"
 * filter, HXCPP to "critical"). ON by default: an unstopped critical error
 * just kills the debugged program with no stop.
 */
public class HaxeCriticalErrorBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HaxeCriticalErrorBreakpointType() {
    super("haxe-critical-error", "Haxe Critical Errors");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any critical error (null access, out-of-bounds, ...)";
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
    breakpoint.setEnabled(true); // on by default: an unstopped critical error just kills the program
    return breakpoint;
  }
}
