package com.intellij.plugins.haxe.runner.debugger.browser;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.client.DapEndpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.*;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.*;
import com.intellij.util.net.NetUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The plumbing shared by the live probes: haxe availability, fixture
 * compilation, adapter/browser locations, request factories, adapter
 * connection with retry, and process-tree teardown.
 */
final class LiveProbeUtil {
  /** The one-page host for the compiled fixture; every probe writes the same file. */
  static final String INDEX_HTML = """
    <!DOCTYPE html><html><head><meta charset='utf-8'></head>\
    <body><script src='app.js'></script></body></html>""";

  /** The pinned js-debug-dap release the probes drive (GitHub release, sha256-verified by the provisioner). */
  static final String JS_DEBUG_VERSION = "1.117.0";

  /** Machine-wide chromium-family install locations, tried after the per-user LOCALAPPDATA ones. */
  private static final List<String> CHROMIUM_PATHS = List.of(
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
    "/snap/bin/chromium");

  private LiveProbeUtil() {
  }

  /** The provisioned js-debug standalone DAP server's entry script. */
  static Path dapServerJs() {
    return nodeRoot().resolve("adapters/js-debug-" + JS_DEBUG_VERSION + "/js-debug/src/dapDebugServer.js");
  }

  /**
   * The browser under test: the {@code WEB_DEBUG_CHROMIUM_EXE} environment
   * variable when set (any chromium-family build — e.g. a provisioned
   * ungoogled-chromium), else an installed Chrome/Edge — mirroring the IDE
   * behaviour, where a blank executable lets js-debug find the default
   * installation. A set-but-invalid path SKIPS rather than silently testing
   * a different browser than the one asked for.
   */
  static Path chromiumExe() {
    String env = System.getenv("WEB_DEBUG_CHROMIUM_EXE");
    if (env != null && !env.isBlank()) {
      Path fromEnv = Path.of(env);
      return Files.isRegularFile(fromEnv) ? fromEnv : null;
    }
    List<Path> candidates = new ArrayList<>();
    String localAppData = System.getenv("LOCALAPPDATA");
    if (localAppData != null && !localAppData.isBlank()) {
      // per-user installs; plain Chromium (e.g. ungoogled-chromium, the
      // reference browser of this module) ahead of the branded ones
      candidates.add(Path.of(localAppData, "Chromium/Application/chrome.exe"));
      candidates.add(Path.of(localAppData, "Google/Chrome/Application/chrome.exe"));
    }
    for (String candidate : CHROMIUM_PATHS) {
      candidates.add(Path.of(candidate));
    }
    for (Path path : candidates) {
      if (Files.isRegularFile(path)) {
        return path;
      }
    }
    return null;
  }

  /** The probes' initialize: the given adapterID, IntelliJ Haxe as the client, startDebugging supported. */
  static InitializeRequest initializeRequest(String adapterId) {
    InitializeRequest initialize = InitializeRequest.standard(adapterId, true);
    initialize.getArguments().setClientName("IntelliJ Haxe");
    return initialize;
  }

  /** The parent-session chrome launch config every browser probe sends. */
  static Map<String, Object> baseLaunchConfig(String baseUrl, Path fixture) {
    Map<String, Object> config = new LinkedHashMap<>();

    config.put("type", "pwa-chrome");
    config.put("request", "launch");
    config.put("name", "probe");
    config.put("url", baseUrl);
    config.put("webRoot", fixture.toString());
    config.put("runtimeExecutable", chromiumExe().toString());
    config.put("runtimeArgs", List.of("--headless=new"));

    return config;
  }

  /** One source breakpoint on the fixture's {@code hxFileName} at {@code line}. */
  static SetBreakpointsRequest breakpointsRequest(Path fixture, String hxFileName, int line) {
    SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
    SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();

    Source source = new Source();
    source.setPath(fixture.resolve(hxFileName).toString());
    source.setName(hxFileName);
    bpArgs.setSource(source);

    SourceBreakpoint bp = new SourceBreakpoint();
    bp.setLine(line);
    bpArgs.setBreakpoints(List.of(bp));

    setBreakpoints.setArguments(bpArgs);
    return setBreakpoints;
  }

  /**
   * Drives the parent session until js-debug asks for the child session:
   * configurationDone on the initialized event, every reverse request answered
   * as the IDE answers it. Null when no startDebugging arrives in time.
   */
  static StartDebuggingRequest awaitStartDebugging(DapClient parent, long millis, long requestTimeout)
    throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      Event event = parent.pollEvent(100);
      if (event instanceof InitializedEvent) {
        parent.sendRequest(new ConfigurationDoneRequest(), requestTimeout);
      }

      Request incoming = parent.pollIncomingRequest(50);
      if (incoming != null) {
        parent.respond(incoming, true);
        if (incoming instanceof StartDebuggingRequest start) {
          return start;
        }
      }
    }
    return null;
  }

  /** Probe-side diagnostics; the tag separates them from the [adapter] and [server] streams. */
  static void probe(String message) {
    System.out.println("[probe] " + message);
  }

  /**
   * Asserts the stop landed on the haxe original at {@code line} — a frame
   * pointing at the generated app.js means the source map was not applied.
   */
  static void assertStoppedInHx(StackFrame top, String hxFile, int line) {
    assertNotNull(top.getSource(), "top frame has no source");
    assertTrue(top.getSource().getPath() != null && top.getSource().getPath().endsWith(hxFile), "top frame is not the .hx original: " + top.getSource().getPath());
    assertTrue(top.getLine() == line, "wrong line: " + top.getLine());
  }

  static StackTraceRequest stackTraceRequest(int threadId) {
    StackTraceRequest request = new StackTraceRequest();
    StackTraceArguments arguments = new StackTraceArguments();
    arguments.setThreadId(threadId);
    request.setArguments(arguments);
    return request;
  }

  static ScopesRequest scopesRequest(int frameId) {
    ScopesRequest request = new ScopesRequest();
    ScopesArguments arguments = new ScopesArguments();
    arguments.setFrameId(frameId);
    request.setArguments(arguments);
    return request;
  }

  static VariablesRequest variablesRequest(int variablesReference) {
    VariablesRequest request = new VariablesRequest();
    VariablesArguments arguments = new VariablesArguments();
    arguments.setVariablesReference(variablesReference);
    request.setArguments(arguments);
    return request;
  }

  static ContinueRequest continueRequest(int threadId) {
    ContinueRequest request = new ContinueRequest();
    ContinueArguments arguments = new ContinueArguments();
    arguments.setThreadId(threadId);
    request.setArguments(arguments);
    return request;
  }

  static PauseRequest pauseRequest(int threadId) {
    PauseRequest request = new PauseRequest();
    PauseArguments arguments = new PauseArguments();
    arguments.setThreadId(threadId);
    request.setArguments(arguments);
    return request;
  }

  static NextRequest nextRequest(int threadId) {
    NextRequest request = new NextRequest();
    NextArguments arguments = new NextArguments();
    arguments.setThreadId(threadId);
    request.setArguments(arguments);
    return request;
  }

  static StepInTargetsRequest stepInTargetsRequest(int frameId) {
    StepInTargetsRequest request = new StepInTargetsRequest();
    StepInTargetsArguments arguments = new StepInTargetsArguments();
    arguments.setFrameId(frameId);
    request.setArguments(arguments);
    return request;
  }

  /** Smart step into: enter the chosen call on the line rather than the first one. */
  static StepInRequest stepInRequest(int threadId, int targetId) {
    StepInRequest request = new StepInRequest();
    StepInArguments arguments = new StepInArguments();
    arguments.setThreadId(threadId);
    arguments.setTargetId(targetId);
    request.setArguments(arguments);
    return request;
  }

  static SetExceptionBreakpointsRequest exceptionBreakpointsRequest(List<String> filters) {
    SetExceptionBreakpointsRequest request = new SetExceptionBreakpointsRequest();
    SetExceptionBreakpointsArguments arguments = new SetExceptionBreakpointsArguments();
    arguments.setFilters(filters);
    request.setArguments(arguments);
    return request;
  }

  /** True when the adapter's initialized event arrives before the timeout. */
  static boolean awaitInitialized(DapEndpoint endpoint, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      if (endpoint.pollEvent(250) instanceof InitializedEvent) {
        return true;
      }
    }
    return false;
  }

  /** The next stopped event, or null when none arrives before the timeout. */
  static StoppedEvent awaitStopped(DapEndpoint endpoint, long millis) throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      if (endpoint.pollEvent(250) instanceof StoppedEvent stopped) {
        return stopped;
      }
    }
    return null;
  }

  /** Where the provisioned node + adapters live; the matrix lanes point elsewhere. */
  static Path nodeRoot() {
    String override = System.getProperty("web.debug.node.root");
    return override != null ? Path.of(override) : Path.of("../../node").toAbsolutePath().normalize();
  }

  static Path nodeExe() {
    // the compat-matrix web lanes point each cell at a provisioned node
    String override = System.getProperty("web.debug.node.exe");
    return override != null ? Path.of(override)
                            : nodeRoot().resolve("node-v24.18.0-win-x64/node.exe");
  }

  static boolean haxeOnPath() {
    try {
      Process probe = new ProcessBuilder("haxe", "--version").redirectErrorStream(true).start();
      return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** The host page plus its compiled app.js — the two files every probe fixture needs. */
  static void writePageAndCompile(Path dir, String mainClass) throws Exception {
    Files.writeString(dir.resolve("index.html"), INDEX_HTML);
    compileHaxeJs(dir, mainClass, "app.js");
  }

  /**
   * Compiles one {@code haxe -js} unit with {@code -debug} (source maps);
   * fails the test with the compiler's output when the compile fails.
   */
  static void compileHaxeJs(Path classPath, String mainClass, String outJsName) throws Exception {
    Process haxe = new ProcessBuilder("haxe", "-cp", classPath.toString(), "-main", mainClass,
                                      "-js", classPath.resolve(outJsName).toString(), "-debug")
      .redirectErrorStream(true)
      .start();

    String output = new String(haxe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!haxe.waitFor(30, TimeUnit.SECONDS) || haxe.exitValue() != 0) {
      throw new AssertionError("fixture compile of " + mainClass + " failed:\n" + output);
    }
  }

  /**
   * A free TCP port from the OS, for the adapter's DAP listener and the RDP
   * port in launch configs. A random pick from a fixed range flakes on
   * Windows: Hyper-V/WinNAT reserve blocks of the port space (excluded port
   * ranges) and a bind inside one dies with EACCES.
   */
  static int freePort() throws IOException {
    return NetUtils.findAvailableSocketPort();
  }

  /** Connects to an adapter's DAP port, retrying briefly (see DapClient.connectWithRetry). */
  static DapClient connectWithRetry(int port, int connectTimeoutMillis) throws IOException {
    return DapClient.connectWithRetry("127.0.0.1", port, connectTimeoutMillis, 10_000);
  }

  /**
   * Kills the WHOLE process tree: killing node does not kill the browser it
   * spawned, and every leaked headless browser poisons later
   * launches.
   */
  static void killTree(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroy();
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
  }
}
