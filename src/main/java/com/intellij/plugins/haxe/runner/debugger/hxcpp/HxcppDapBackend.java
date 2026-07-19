package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import java.io.Closeable;
import java.io.IOException;

/**
 * The connection strategy behind an HXCPP debug session. {@link
 * HxcppDebugProcess} is a plain DAP client either way; what differs is who the
 * DAP peer is and how it comes up:
 *
 * <ul>
 *   <li>{@link HxcppVshaxeBackend} — the in-process {@code HxcppDebugAdapter}
 *       translating DAP to the vshaxe hxcpp-debug-server wire protocol; the
 *       debuggee's port is a compile-time define.</li>
 *   <li>{@code HxcppIntellijBackend} — the debuggee's embedded
 *       {@code intellij-hxcpp-debug-server} speaks DAP natively and connects
 *       OUT to a listener the runner bound on an ephemeral port before
 *       spawning it (handed over via HXCPP_DEBUG_HOST/PORT env vars).</li>
 * </ul>
 *
 * A backend is created by the debug runner BEFORE the debuggee is spawned (it
 * must be listening when the debuggee starts connecting) and closed by the
 * debug process on teardown.
 */
public interface HxcppDapBackend extends Closeable {

  /**
   * Blocks until the DAP peer is ready and returns the client for the session.
   * Called once, on the debug process's request thread.
   */
  DapClient connect() throws IOException;

  /**
   * Whether the wire protocol includes a launch request (the vshaxe adapter's
   * does; the IntelliJ server considers the debuggee launched once connected).
   */
  boolean requiresLaunchRequest();

  /**
   * Whether the server understands the {@code setExceptionBreakpoints}
   * uncaught/critical filters (and thus whether the exception breakpoint
   * types should drive this session).
   */
  boolean supportsExceptionFilters();

  /**
   * Whether the server understands the custom {@code intellij/stepIntoFunction}
   * request (smart step into with IDE-resolved targets).
   */
  boolean supportsSmartStepInto();

  /**
   * Whether the server understands the custom
   * {@code intellij/setToStringRendering} request (object labels via the
   * object's own toString, toggleable live). The vshaxe server does not —
   * it always renders through Std.string and offers no control.
   */
  boolean supportsToStringRendering();

  /**
   * Whether the server understands the custom
   * {@code intellij/setExpressionStepping} request (eval only: each step
   * becomes one raw interpreter sub-step and stack frames carry the exact
   * expression span for the editor highlight).
   */
  default boolean supportsExpressionStepping() {
    return false;
  }

  /** Appended to the "program exited before the debugger could attach" failure. */
  String startupHint();
}
