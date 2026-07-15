package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HashLink VM exceptions" breakpoint: stops on a VM-RAISED error — a null
 * access, array-out-of-bounds, invalid cast or division by zero — which
 * HashLink's C runtime raises through {@code hl_throw} WITHOUT executing a
 * bytecode throw, so every OThrow-based exception breakpoint misses it and the
 * program terminates with no stop. Drives the "vm" filter of
 * {@code setExceptionBreakpoints}; the stop carries the VM's own message
 * ("Null access .length"). Named "VM", the party that raises them: "native"
 * reads as the HXCPP/C native target, and every exception happens "at runtime".
 *
 * Its own category (rather than a row under "HashLink Uncaught Exceptions"):
 * the platform allows one default breakpoint per type, and only default
 * breakpoints are undeletable — a programmatically added sibling could be
 * removed from the dialog. ENABLED by default: a VM error otherwise kills the
 * debugged program with no stop, which is never what a debugging user wants.
 */
public class HashLinkVmExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HashLinkVmExceptionBreakpointType() {
    super("hashlink-vm-exception", "HashLink VM Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any HashLink VM exception (null access, out-of-bounds, ...)";
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
    breakpoint.setEnabled(true); // on by default: an unstopped VM error just kills the program
    return breakpoint;
  }
}
