package com.intellij.plugins.haxe.runner.debugger.browser;

import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.awaitStartDebugging;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.breakpointsRequest;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.compileHaxeJs;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.dapServerJs;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.haxeOnPath;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.initializeRequest;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.nodeExe;
import static com.intellij.plugins.haxe.runner.debugger.browser.LiveProbeUtil.probe;
import static org.junit.jupiter.api.Assertions.*;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.*;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wire probe for the NODE test-debug lane: the debuggee is
 * {@code node --inspect-brk=<port> app.js} spawned by the PROBE (as the test
 * runner spawns it), and vscode-js-debug ATTACHES ({@code pwa-node}). Pins
 * what {@code NodeTestDebugBackend} relies on:
 *
 * <ul>
 *   <li>the attach flavour runs the same parent/child dance as the browser
 *       launch (fire-and-forget attach, startDebugging hand-over);</li>
 *   <li>{@code continueOnAttach} releases the --inspect-brk hold after
 *       configurationDone — breakpoints installed first stop the run;</li>
 *   <li>{@code resolveSourceMapLocations=null} lets the map of an artifact
 *       OUTSIDE the cwd resolve (the gutter single-run temp root);</li>
 *   <li>the debuggee's trace output stays on ITS process stdout — the SM
 *       test console needs no output replay from the debug connection.</li>
 * </ul>
 *
 * Skips when node or the adapter are not provisioned under {@code <root>/node}.
 */
@DisplayName("Browser debugger: node attach (live)")
public class NodeAttachLiveProbe {
  private static final long TIMEOUT = 15_000;

  private static final int BP_LINE = 3;
  private static final String NODE_MAIN_HX = """
    class NodeMain {
    	static function tick() {
    		trace("tick"); // BP_LINE = 3
    	}
    	static function main() {
    		trace("start");
    		tick();
    		trace("end");
    	}
    }
    """;

  private Process adapter;
  private int adapterPort;
  private DapClient parent;
  private Process debuggee;
  private final List<String> debuggeeStdout = new CopyOnWriteArrayList<>();

  @BeforeEach
  public void spawnAdapter() throws IOException {
    Assumptions.assumeTrue(Files.isRegularFile(nodeExe()), "portable node not provisioned - skipping");
    Assumptions.assumeTrue(Files.isRegularFile(dapServerJs()), "js-debug adapter not provisioned - skipping");

    adapterPort = LiveProbeUtil.freePort();
    adapter = new ProcessBuilder(nodeExe().toString(), dapServerJs().toString(),
                                 String.valueOf(adapterPort), "127.0.0.1")
      .directory(dapServerJs().getParent().toFile())
      .redirectErrorStream(true)
      .start();

    BufferedReader stdout = new BufferedReader(
      new InputStreamReader(adapter.getInputStream(), StandardCharsets.UTF_8));
    String line = stdout.readLine();
    probe("[adapter] " + line);
    assertNotNull(line, "adapter announced nothing (died?)");
    assertTrue(line.contains("Debug server listening"), "unexpected announcement: " + line);
    gobble(stdout, "[adapter] ", null);

    parent = LiveProbeUtil.connectWithRetry(adapterPort, (int)TIMEOUT);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (parent != null) {
      try {
        parent.close();
      } catch (IOException ignored) {
      }
    }
    if (debuggee != null) {
      LiveProbeUtil.killTree(debuggee);
    }
    if (adapter != null) {
      LiveProbeUtil.killTree(adapter);
    }
  }

  /**
   * The full lane: hold at --inspect-brk, attach, breakpoint by .hx path over
   * the source map, stop on the test line, resume to a clean exit with the
   * trace output on the DEBUGGEE's stdout.
   */
  @Test
  @Timeout(90)
  @DisplayName("attach stops on a haxe breakpoint and output stays on stdout")
  public void attachStopsOnAHaxeBreakpointAndOutputStaysOnStdout() throws Exception {
    Assumptions.assumeTrue(haxeOnPath(), "haxe not on PATH - skipping");
    // the artifact deliberately lives OUTSIDE the debuggee's cwd, replicating
    // the gutter single-run temp root - resolveSourceMapLocations=null is
    // what makes its map resolvable
    Path fixture = Files.createTempDirectory("haxe-node-attach");
    Path cwd = Files.createTempDirectory("haxe-node-cwd");
    Files.writeString(fixture.resolve("NodeMain.hx"), NODE_MAIN_HX);
    compileHaxeJs(fixture, "NodeMain", "app.js");

    int inspectorPort = LiveProbeUtil.freePort();
    debuggee = new ProcessBuilder(nodeExe().toString(),
                                  "--inspect-brk=" + inspectorPort,
                                  fixture.resolve("app.js").toString())
      .directory(cwd.toFile())
      .start();
    gobble(new BufferedReader(new InputStreamReader(debuggee.getInputStream(), StandardCharsets.UTF_8)),
           "[debuggee] ", debuggeeStdout);
    gobble(new BufferedReader(new InputStreamReader(debuggee.getErrorStream(), StandardCharsets.UTF_8)),
           "[debuggee-err] ", null);

    assertTrue(parent.sendRequest(initializeRequest("node"), TIMEOUT).isSuccess(), "parent initialize");
    parent.sendRequestNoWait(ConfiguredLaunchRequest.of(attachConfig(inspectorPort, cwd)));
    StartDebuggingRequest startDebugging = awaitStartDebugging(parent, 20_000, TIMEOUT);
    assertNotNull(startDebugging, "no startDebugging reverse request for the node attach");

    try (DapClient child = LiveProbeUtil.connectWithRetry(adapterPort, (int)TIMEOUT)) {
      assertTrue(child.sendRequest(initializeRequest("node"), TIMEOUT).isSuccess(), "child initialize");
      child.sendRequestNoWait(ConfiguredLaunchRequest.of(startDebugging.getArguments().getConfiguration()));

      StoppedEvent stopped = configureAndAwaitBreakpoint(child, fixture);
      assertNotNull(stopped, "no breakpoint stop (continueOnAttach or the source map failed)");

      StackTraceResponse stack = (StackTraceResponse)child.sendRequest(
        LiveProbeUtil.stackTraceRequest(stopped.getBody().getThreadId()), TIMEOUT);
      StackFrame top = stack.getBody().getStackFrames().get(0);
      LiveProbeUtil.assertStoppedInHx(top, "NodeMain.hx", BP_LINE);
      probe("stopped at " + top.getSource().getPath() + ":" + top.getLine());

      child.sendRequest(LiveProbeUtil.continueRequest(stopped.getBody().getThreadId()), TIMEOUT);
    }

    assertTrue(debuggee.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "debuggee did not exit");
    assertEquals(0, debuggee.exitValue(), "debuggee exit code");
    String output = String.join("\n", debuggeeStdout);
    boolean traced = output.contains("start") && output.contains("tick") && output.contains("end");
    assertTrue(traced, "trace output must arrive on the debuggee's own stdout: " + output);
  }

  /** Breakpoint + configurationDone on the child's initialized event, then the breakpoint stop (entry stops resumed). */
  private StoppedEvent configureAndAwaitBreakpoint(DapClient child, Path fixture) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    boolean configured = false;
    while (System.currentTimeMillis() < deadline) {
      Event event = child.pollEvent(100);
      if (event instanceof InitializedEvent && !configured) {
        configured = true;
        assertTrue(child.sendRequest(breakpointsRequest(fixture, "NodeMain.hx", BP_LINE), TIMEOUT).isSuccess(),
                   "setBreakpoints");
        child.sendRequest(new ConfigurationDoneRequest(), TIMEOUT);
      }
      if (event instanceof StoppedEvent stopped) {
        if ("breakpoint".equals(stopped.getBody().getReason())) {
          return stopped;
        }
        // an entry/pause stop surfacing despite continueOnAttach: release it
        probe("non-breakpoint stop (" + stopped.getBody().getReason() + ") - continuing");
        child.sendRequest(LiveProbeUtil.continueRequest(stopped.getBody().getThreadId()), TIMEOUT);
      }
    }
    return null;
  }

  /** The attach flavour of the parent config — what NodeTestDebugBackend sends. */
  private static Map<String, Object> attachConfig(int inspectorPort, Path cwd) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("type", "pwa-node");
    config.put("request", "attach");
    config.put("name", "probe");
    config.put("port", inspectorPort);
    config.put("continueOnAttach", true);
    // the match-all glob, never null: the DAP encoder drops null values
    config.put("resolveSourceMapLocations", List.of("**"));
    config.put("cwd", cwd.toString());
    return config;
  }

  private static void gobble(BufferedReader reader, String tag, List<String> sink) {
    Thread gobbler = new Thread(() -> {
      try (BufferedReader stdout = reader) {
        String out;
        while ((out = stdout.readLine()) != null) {
          probe(tag + out);
          if (sink != null) {
            sink.add(out);
          }
        }
      } catch (IOException ignored) {
      }
    }, "node-probe-gobbler");
    gobbler.setDaemon(true);
    gobbler.start();
  }
}
