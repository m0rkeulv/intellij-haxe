package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent state distinguishing the two rows of the "HashLink Uncaught
 * Exceptions" category: the plain "any uncaught exception" breakpoint
 * ({@code runtimeErrors} false) and the "runtime exceptions" one (true) that
 * stops on VM-raised errors — null access, out-of-bounds, invalid cast,
 * division by zero. One category with two toggles, because the platform allows
 * only a single default breakpoint per {@code XBreakpointType}: the runtime row
 * is a property-flagged sibling installed by {@link HashLinkRuntimeBreakpointStartActivity}.
 */
public class HashLinkUncaughtExceptionProperties
  extends XBreakpointProperties<HashLinkUncaughtExceptionProperties> {

  @Attribute("runtimeErrors")
  public boolean runtimeErrors;

  @Override
  public @Nullable HashLinkUncaughtExceptionProperties getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull HashLinkUncaughtExceptionProperties state) {
    runtimeErrors = state.runtimeErrors;
  }
}
