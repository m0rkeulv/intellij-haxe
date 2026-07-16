package com.intellij.plugins.haxe.runner.debugger.exceptions;

import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Haxe Thrown Exceptions": stop where an exception is thrown, even when it
 * will be caught — shared by the HashLink and HXCPP debuggers. Per-target
 * nuance: HashLink stops on EVERY bytecode throw ("all" filter); HXCPP stops
 * where a {@code haxe.Exception} (or subclass) is constructed — normally the
 * throw expression — so raw-value throws ({@code throw "str"}, enums) are not
 * visible there ("thrown" filter). OFF by default: exception-heavy code would
 * stop constantly.
 */
public class HaxeThrownExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HaxeThrownExceptionBreakpointType() {
    super("haxe-thrown-exception", "Haxe Thrown Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any exception";
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
    breakpoint.setEnabled(false); // off by default; the user opts in
    return breakpoint;
  }
}
