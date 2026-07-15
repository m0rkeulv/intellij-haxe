package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The "HashLink native exceptions" breakpoint: stops on a VM-RAISED error — a
 * null access, array-out-of-bounds, invalid cast or division by zero — which
 * HashLink's C runtime raises through {@code hl_throw} WITHOUT executing a
 * bytecode {@code OThrow}. The other exception breakpoints only trap OThrow
 * sites, so those runtime errors escape them and terminate the program; this
 * one traps hl_throw itself. Enabling/disabling it drives the "native" filter
 * of {@code setExceptionBreakpoints}; present by default but off.
 */
public class HashLinkNativeExceptionBreakpointType
  extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

  public HashLinkNativeExceptionBreakpointType() {
    super("hashlink-native-exception", "HashLink Native Exceptions");
  }

  @Override
  public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
    return "Any HashLink native exception (null access, out-of-bounds, ...)";
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
