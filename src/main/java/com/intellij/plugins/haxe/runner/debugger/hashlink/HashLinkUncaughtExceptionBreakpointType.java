package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HashLink Uncaught Exceptions" category holds two toggles:
 * <ul>
 *   <li>"Any uncaught HashLink exception" (the default breakpoint): stops at a
 *   bytecode throw that no live {@code try/catch} will handle — the "uncaught"
 *   filter of {@code setExceptionBreakpoints}.</li>
 *   <li>"HashLink VM exceptions" (a property-flagged sibling installed by
 *   {@link HashLinkVmBreakpointStartActivity}): stops on VM-RAISED errors —
 *   null access, out-of-bounds, invalid cast, division by zero — which
 *   HashLink's C runtime raises through {@code hl_throw} WITHOUT a bytecode
 *   throw, so every OThrow-based breakpoint misses them and the program
 *   terminates with no stop. Drives the "vm" filter. (Named "VM", the party
 *   that raises them: "native" reads as the HXCPP/C native target, and every
 *   exception happens "at runtime".)</li>
 * </ul>
 * Both present by default but off.
 */
public class HashLinkUncaughtExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<HashLinkUncaughtExceptionProperties>, HashLinkUncaughtExceptionProperties> {

  public HashLinkUncaughtExceptionBreakpointType() {
    super("hashlink-uncaught-exception", "HashLink Uncaught Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<HashLinkUncaughtExceptionProperties> breakpoint) {
    return isVmErrors(breakpoint)
           ? "HashLink VM exceptions (null access, out-of-bounds, ...)"
           : "Any uncaught HashLink exception";
  }

  /** True for the VM-errors row, false for the plain uncaught one. */
  public static boolean isVmErrors(@NotNull XBreakpoint<HashLinkUncaughtExceptionProperties> breakpoint) {
    HashLinkUncaughtExceptionProperties properties = breakpoint.getProperties();
    return properties != null && properties.vmErrors;
  }

  @Override
  public @Nullable HashLinkUncaughtExceptionProperties createProperties() {
    return new HashLinkUncaughtExceptionProperties();
  }

  @Override
  public boolean isAddBreakpointButtonVisible() {
    return false;
  }

  @Override
  public XBreakpoint<HashLinkUncaughtExceptionProperties> createDefaultBreakpoint(@NotNull XBreakpointCreator<HashLinkUncaughtExceptionProperties> creator) {
    XBreakpoint<HashLinkUncaughtExceptionProperties> breakpoint =
      creator.createBreakpoint(new HashLinkUncaughtExceptionProperties());
    breakpoint.setEnabled(false); // off by default; the user opts in
    return breakpoint;
  }
}
