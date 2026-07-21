package com.intellij.plugins.haxe.runner.debugger.browser;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.OutputEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfiguredLaunchRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.DisconnectRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequestArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StartDebuggingRequest;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * M2 wire probe for vscode-js-debug's standalone DAP server (pinned
 * js-debug-dap v1.117.0, sha256 ad8d04ed..., from the GitHub release), driving
 * an UNGOOGLED-CHROMIUM fork — the user's chosen first Chromium target. Pins
 * the parts that differ from the firefox adapter:
 *
 * <ul>
 *   <li>launch ordering (does the launch response wait for configurationDone?);</li>
 *   <li>the {@code startDebugging} REVERSE request and the child-session
 *       handshake via {@code __pendingTargetId} on a SECOND connection;</li>
 *   <li>breakpoint-by-.hx-path over source maps in the child session.</li>
 * </ul>
 *
 * Skips when node/adapter/chromium are not provisioned under {@code <root>/node}.
 */
public class JsDebugAdapterLiveProbe {
  private static final long TIMEOUT = 15_000;
  private static final int BP_LINE = 5;
  private static final String WEB_MAIN_HX = """
    class WebMain {
    	static var counter = 0;

    	static function tick() {
    		counter++; // BP_LINE = 5
    		var label = "tick-" + counter;
    		js.Browser.console.log(label);
    	}

    	static function main() {
    		js.Browser.window.setInterval(tick, 250);
    	}
    }
    """;

  private Process adapter;
  private int adapterPort;
  private DapClient parent;

  private static Path nodeRoot() {
    String override = System.getProperty("web.debug.node.root");
    return override != null ? Path.of(override) : Path.of("../../node").toAbsolutePath().normalize();
  }

  private static Path nodeExe() {
    return nodeRoot().resolve("node-v24.18.0-win-x64/node.exe");
  }

  private static Path dapServerJs() {
    return nodeRoot().resolve("adapters/js-debug-1.117.0/js-debug/src/dapDebugServer.js");
  }

  private static Path chromiumExe() {
    return nodeRoot().resolve("ungoogled-chromium_150.0.7871.128-1.1_windows_x64/chrome.exe");
  }

  private static boolean haxeOnPath() {
    try {
      Process probe = new ProcessBuilder("haxe", "--version").redirectErrorStream(true).start();
      return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Before
  public void spawnAdapter() throws IOException {
    Assume.assumeTrue("portable node not provisioned - skipping", Files.isRegularFile(nodeExe()));
    Assume.assumeTrue("js-debug adapter not provisioned - skipping", Files.isRegularFile(dapServerJs()));
    Assume.assumeTrue("ungoogled-chromium not provisioned - skipping", Files.isRegularFile(chromiumExe()));

    adapterPort = ThreadLocalRandom.current().nextInt(20000, 60000);
    adapter = new ProcessBuilder(nodeExe().toString(), dapServerJs().toString(),
                                 String.valueOf(adapterPort), "127.0.0.1")
      .directory(dapServerJs().getParent().toFile())
      .redirectErrorStream(true)
      .start();
    BufferedReader stdout = new BufferedReader(
      new InputStreamReader(adapter.getInputStream(), StandardCharsets.UTF_8));
    String line = stdout.readLine();
    System.out.println("[adapter] " + line);
    assertNotNull("adapter announced nothing (died?)", line);
    assertTrue("unexpected announcement: " + line, line.contains("Debug server listening"));
    Thread gobbler = new Thread(() -> {
      try {
        String out;
        while ((out = stdout.readLine()) != null) {
          System.out.println("[adapter] " + out);
        }
      } catch (IOException ignored) {
      }
    }, "js-debug-gobbler");
    gobbler.setDaemon(true);
    gobbler.start();

    parent = connectWithRetry(adapterPort);
  }

  private static DapClient connectWithRetry(int port) throws IOException {
    long deadline = System.currentTimeMillis() + 10_000;
    IOException last = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        return DapClient.connect("127.0.0.1", port, (int)TIMEOUT);
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while connecting", ie);
        }
      }
    }
    throw last != null ? last : new IOException("could not connect to the adapter");
  }

  @After
  public void tearDown() throws Exception {
    if (parent != null) {
      try {
        parent.close();
      } catch (IOException ignored) {
      }
    }
    if (adapter != null) {
      adapter.descendants().forEach(ProcessHandle::destroyForcibly);
      adapter.destroy();
      if (!adapter.waitFor(3, TimeUnit.SECONDS)) {
        adapter.destroyForcibly();
        adapter.waitFor(3, TimeUnit.SECONDS);
      }
    }
  }

  private static Path buildFixture() throws Exception {
    Path dir = Files.createTempDirectory("haxe-jsdbg-probe");
    Files.writeString(dir.resolve("WebMain.hx"), WEB_MAIN_HX);
    Files.writeString(dir.resolve("index.html"),
                      "<!DOCTYPE html><html><head><meta charset='utf-8'></head>"
                      + "<body><script src='app.js'></script></body></html>");
    Process haxe = new ProcessBuilder("haxe", "-cp", dir.toString(), "-main", "WebMain",
                                      "-js", dir.resolve("app.js").toString(), "-debug")
      .redirectErrorStream(true).start();
    String output = new String(haxe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!haxe.waitFor(30, TimeUnit.SECONDS) || haxe.exitValue() != 0) {
      throw new AssertionError("fixture compile failed:\n" + output);
    }
    return dir;
  }

  private static InitializeRequest initializeRequest() {
    InitializeRequest initialize = new InitializeRequest();
    InitializeRequestArguments arguments = new InitializeRequestArguments();
    arguments.setClientID("intellij");
    arguments.setClientName("IntelliJ Haxe");
    arguments.setAdapterID("chrome");
    arguments.setPathFormat("path");
    arguments.setLinesStartAt1(true);
    arguments.setColumnsStartAt1(true);
    arguments.setSupportsStartDebuggingRequest(true);
    initialize.setArguments(arguments);
    return initialize;
  }

  private SetBreakpointsRequest breakpointsRequest(Path fixture) {
    SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
    SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();
    Source source = new Source();
    source.setPath(fixture.resolve("WebMain.hx").toString());
    source.setName("WebMain.hx");
    bpArgs.setSource(source);
    SourceBreakpoint bp = new SourceBreakpoint();
    bp.setLine(BP_LINE);
    bpArgs.setBreakpoints(List.of(bp));
    setBreakpoints.setArguments(bpArgs);
    return setBreakpoints;
  }

  private static final int LOAD_BP_LINE = 3;
  private static final String WEB_LOAD_HX = """
    class WebLoad {
    	static function main() {
    		var marker = "before"; // LOAD_BP_LINE = 3
    		js.Browser.console.log(marker + "-loaded");
    	}
    }
    """;

  /**
   * Load-time code (main body, runs during page load) — the case the firefox
   * adapter needed the refresh-once trick for. js-debug pre-registers
   * breakpoints through CDP before scripts execute, so the FIRST load must
   * stop, with no serving tricks. Guards the family split in the backend
   * (no refreshFirstPage for chromium).
   */
  @Test(timeout = 180_000)
  public void loadTimeBreakpointHitsOnFirstLoad() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path fixture = Files.createTempDirectory("haxe-jsdbg-load");
    Files.writeString(fixture.resolve("WebLoad.hx"), WEB_LOAD_HX);
    Files.writeString(fixture.resolve("index.html"),
                      "<!DOCTYPE html><html><head><meta charset='utf-8'></head>"
                      + "<body><script src='app.js'></script></body></html>");
    Process haxe = new ProcessBuilder("haxe", "-cp", fixture.toString(), "-main", "WebLoad",
                                      "-js", fixture.resolve("app.js").toString(), "-debug")
      .redirectErrorStream(true).start();
    assertTrue("fixture compile", haxe.waitFor(30, TimeUnit.SECONDS) && haxe.exitValue() == 0);

    StackFrame top = driveSessionToStop(fixture, "WebLoad.hx", LOAD_BP_LINE);
    assertTrue("stopped in the load-time .hx line: " + top.getSource().getPath() + ":" + top.getLine(),
               top.getSource().getPath().endsWith("WebLoad.hx") && top.getLine() == LOAD_BP_LINE);
  }

  /** The parent+child flow, shared by the probes; returns the stop's top frame. */
  private StackFrame driveSessionToStop(Path fixture, String bpFileName, int bpLine) throws Exception {
    try (ContentHttpServer content = new ContentHttpServer(fixture)) {
      assertTrue("parent initialize", parent.sendRequest(initializeRequest(), TIMEOUT).isSuccess());

      Map<String, Object> launchConfig = new LinkedHashMap<>();
      launchConfig.put("type", "pwa-chrome");
      launchConfig.put("request", "launch");
      launchConfig.put("name", "probe");
      launchConfig.put("url", content.getBaseUrl());
      launchConfig.put("webRoot", fixture.toString());
      launchConfig.put("runtimeExecutable", chromiumExe().toString());
      launchConfig.put("runtimeArgs", List.of("--headless=new"));
      parent.sendRequestNoWait(ConfiguredLaunchRequest.of(launchConfig));

      StartDebuggingRequest startDebugging = null;
      long deadline = System.currentTimeMillis() + 60_000;
      while (System.currentTimeMillis() < deadline && startDebugging == null) {
        Event event = parent.pollEvent(100);
        if (event instanceof InitializedEvent) {
          parent.sendRequest(new ConfigurationDoneRequest(), TIMEOUT);
        }
        Request incoming = parent.pollIncomingRequest(50);
        if (incoming != null) {
          parent.respond(incoming, true);
          if (incoming instanceof StartDebuggingRequest start) {
            startDebugging = start;
          }
        }
      }
      assertNotNull("no startDebugging reverse request", startDebugging);

      try (DapClient child = connectWithRetry(adapterPort)) {
        assertTrue("child initialize", child.sendRequest(initializeRequest(), TIMEOUT).isSuccess());
        child.sendRequestNoWait(ConfiguredLaunchRequest.of(startDebugging.getArguments().getConfiguration()));

        boolean childConfigured = false;
        StoppedEvent stopped = null;
        deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && stopped == null) {
          Event event = child.pollEvent(100);
          if (event instanceof InitializedEvent && !childConfigured) {
            childConfigured = true;
            SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
            SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();
            Source source = new Source();
            source.setPath(fixture.resolve(bpFileName).toString());
            source.setName(bpFileName);
            bpArgs.setSource(source);
            SourceBreakpoint bp = new SourceBreakpoint();
            bp.setLine(bpLine);
            bpArgs.setBreakpoints(List.of(bp));
            setBreakpoints.setArguments(bpArgs);
            assertTrue("child setBreakpoints", child.sendRequest(setBreakpoints, TIMEOUT).isSuccess());
            assertTrue("child configurationDone",
                       child.sendRequest(new ConfigurationDoneRequest(), TIMEOUT).isSuccess());
          }
          if (event instanceof StoppedEvent s) {
            stopped = s;
          }
        }
        assertNotNull("breakpoint never hit", stopped);
        int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 1;
        StackTraceRequest stackTrace = new StackTraceRequest();
        StackTraceArguments stArgs = new StackTraceArguments();
        stArgs.setThreadId(threadId);
        stackTrace.setArguments(stArgs);
        Response stResponse = child.sendRequest(stackTrace, TIMEOUT);
        assertTrue("child stackTrace", stResponse.isSuccess());
        List<StackFrame> frames = ((StackTraceResponse)stResponse).getBody().getStackFrames();
        assertTrue("no frames", !frames.isEmpty());
        StackFrame top = frames.get(0);
        System.out.println("[probe] top frame: " + top.getName() + " @ "
                           + (top.getSource() != null ? top.getSource().getPath() : "?") + ":" + top.getLine());
        assertNotNull("top frame has no source", top.getSource());
        child.sendRequest(new DisconnectRequest(), TIMEOUT);
        return top;
      }
    }
  }

  @Test(timeout = 180_000)
  public void fullSessionWithChildViaStartDebugging() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path fixture = buildFixture();

    try (ContentHttpServer content = new ContentHttpServer(fixture)) {
      content.setRequestListener(line -> System.out.println("[server] " + line));

      // --- parent session ---
      Response initResponse = parent.sendRequest(initializeRequest(), TIMEOUT);
      System.out.println("[probe] parent initialize success=" + initResponse.isSuccess());
      assertTrue("parent initialize", initResponse.isSuccess());

      Map<String, Object> launchConfig = new LinkedHashMap<>();
      launchConfig.put("type", "pwa-chrome");
      launchConfig.put("request", "launch");
      launchConfig.put("name", "probe");
      launchConfig.put("url", content.getBaseUrl());
      launchConfig.put("webRoot", fixture.toString());
      launchConfig.put("runtimeExecutable", chromiumExe().toString());
      launchConfig.put("runtimeArgs", List.of("--headless=new"));

      // ORDERING QUESTION: js-debug may hold the launch response until
      // configurationDone - send launch on a helper thread and observe
      CompletableFuture<Response> launchFuture = new CompletableFuture<>();
      Thread launcher = new Thread(() -> {
        try {
          launchFuture.complete(parent.sendRequest(ConfiguredLaunchRequest.of(launchConfig), 60_000));
        } catch (Exception e) {
          launchFuture.completeExceptionally(e);
        }
      }, "probe-launch");
      launcher.setDaemon(true);
      launcher.start();

      // drive the parent: initialized -> breakpoints + configurationDone;
      // meanwhile watch for the startDebugging reverse request
      StartDebuggingRequest startDebugging = null;
      boolean parentConfigured = false;
      long deadline = System.currentTimeMillis() + 60_000;
      while (System.currentTimeMillis() < deadline && startDebugging == null) {
        Event event = parent.pollEvent(100);
        if (event != null) {
          System.out.println("[probe] parent event '" + event.getEvent() + "'"
                             + (event instanceof OutputEvent o ? " :: " + o.getBody().getOutput() : ""));
          if (event instanceof InitializedEvent && !parentConfigured) {
            parentConfigured = true;
            System.out.println("[probe] parent setBreakpoints success="
                               + parent.sendRequest(breakpointsRequest(fixture), TIMEOUT).isSuccess());
            System.out.println("[probe] parent configurationDone success="
                               + parent.sendRequest(new ConfigurationDoneRequest(), TIMEOUT).isSuccess());
          }
        }
        Request incoming = parent.pollIncomingRequest(50);
        if (incoming != null) {
          System.out.println("[probe] parent REVERSE request '" + incoming.getCommand() + "'");
          if (incoming instanceof StartDebuggingRequest start) {
            startDebugging = start;
            parent.respond(incoming, true);
          } else {
            parent.respond(incoming, true);
          }
        }
        if (launchFuture.isDone() && !launchFuture.isCompletedExceptionally()) {
          // just report once - the loop condition is the reverse request
        }
      }
      System.out.println("[probe] launch settled=" + launchFuture.isDone()
                         + " startDebugging=" + (startDebugging != null));
      assertNotNull("no startDebugging reverse request from js-debug", startDebugging);
      Map<String, Object> childConfig = startDebugging.getArguments().getConfiguration();
      System.out.println("[probe] child config keys=" + childConfig.keySet());

      // --- child session (second connection, config from the reverse request) ---
      try (DapClient child = connectWithRetry(adapterPort)) {
        assertTrue("child initialize", child.sendRequest(initializeRequest(), TIMEOUT).isSuccess());

        CompletableFuture<Response> childLaunchFuture = new CompletableFuture<>();
        Thread childLauncher = new Thread(() -> {
          try {
            childLaunchFuture.complete(child.sendRequest(ConfiguredLaunchRequest.of(childConfig), 60_000));
          } catch (Exception e) {
            childLaunchFuture.completeExceptionally(e);
          }
        }, "probe-child-launch");
        childLauncher.setDaemon(true);
        childLauncher.start();

        boolean childConfigured = false;
        StoppedEvent stopped = null;
        deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && stopped == null) {
          Event event = child.pollEvent(100);
          if (event == null) {
            continue;
          }
          System.out.println("[probe] child event '" + event.getEvent() + "'"
                             + (event instanceof StoppedEvent s ? " reason=" + s.getBody().getReason() : ""));
          if (event instanceof InitializedEvent && !childConfigured) {
            childConfigured = true;
            System.out.println("[probe] child launch settled BEFORE configuration: " + childLaunchFuture.isDone());
            System.out.println("[probe] child setBreakpoints success="
                               + child.sendRequest(breakpointsRequest(fixture), TIMEOUT).isSuccess());
            System.out.println("[probe] child configurationDone success="
                               + child.sendRequest(new ConfigurationDoneRequest(), TIMEOUT).isSuccess());
            System.out.println("[probe] child launch settled AFTER configurationDone: " + childLaunchFuture.isDone());
          }
          if (event instanceof StoppedEvent s) {
            stopped = s;
          }
        }
        assertNotNull("breakpoint never hit in the child session", stopped);
        System.out.println("[probe] at stop: parent launch settled=" + launchFuture.isDone()
                           + " child launch settled=" + childLaunchFuture.isDone());
        int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 1;

        StackTraceRequest stackTrace = new StackTraceRequest();
        StackTraceArguments stArgs = new StackTraceArguments();
        stArgs.setThreadId(threadId);
        stackTrace.setArguments(stArgs);
        Response stResponse = child.sendRequest(stackTrace, TIMEOUT);
        assertTrue("child stackTrace", stResponse.isSuccess());
        List<StackFrame> frames = ((StackTraceResponse)stResponse).getBody().getStackFrames();
        assertTrue("no frames", !frames.isEmpty());
        StackFrame top = frames.get(0);
        System.out.println("[probe] child top frame: " + top.getName() + " @ "
                           + (top.getSource() != null ? top.getSource().getPath() : "?") + ":" + top.getLine());
        assertNotNull("top frame has no source", top.getSource());
        assertTrue("top frame is not the .hx original: " + top.getSource().getPath(),
                   top.getSource().getPath() != null && top.getSource().getPath().endsWith("WebMain.hx"));
        assertTrue("wrong line: " + top.getLine(), top.getLine() == BP_LINE);

        child.sendRequest(new DisconnectRequest(), TIMEOUT);
      }
      parent.sendRequest(new DisconnectRequest(), TIMEOUT);
    }
  }
}
