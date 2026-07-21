package com.intellij.plugins.haxe.runner.debugger.browser;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.DisconnectRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequestArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ThreadsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.InitializeResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetBreakpointsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * M0 wire probe for the vscode-firefox-debug adapter (web-debugger project):
 * spawns the PINNED adapter bundle (Open VSX 2.15.0, sha256 f72f7443...) on the
 * local portable node, in {@code --server=<port>} TCP mode, and drives it with
 * the SAME DapClient the IDE backends use. Verifies our framing/decoding against
 * a foreign adapter and records the initialize capabilities + event ordering.
 *
 * Skips (does not fail) when node or the adapter are not provisioned — they are
 * user-provisioned under {@code <project>/node/} (git-ignored), see the
 * web-debugger plan.
 */
public class FirefoxAdapterLiveProbe {
  private static final long TIMEOUT = 15_000;

  private Process adapter;
  private int adapterPort;
  private DapClient client;

  /** {@code <root>/node} — provided by the gradle test task; falls back for IDE runs. */
  private static Path nodeRoot() {
    String override = System.getProperty("web.debug.node.root");
    return override != null ? Path.of(override) : Path.of("../../node").toAbsolutePath().normalize();
  }

  private static Path nodeExe() {
    return nodeRoot().resolve("node-v24.18.0-win-x64/node.exe");
  }

  private static Path adapterBundle() {
    return nodeRoot().resolve("adapters/vscode-firefox-debug-2.15.0/extension/dist/adapter.bundle.js");
  }

  @Before
  public void spawnAdapter() throws IOException {
    Assume.assumeTrue("portable node not provisioned - skipping", Files.isRegularFile(nodeExe()));
    Assume.assumeTrue("firefox adapter not provisioned - skipping", Files.isRegularFile(adapterBundle()));

    int port = ThreadLocalRandom.current().nextInt(20000, 60000);
    adapter = new ProcessBuilder(nodeExe().toString(), adapterBundle().toString(), "--server=" + port)
      // cwd = dist so the bundle finds mappings.wasm however it resolves it
      .directory(adapterBundle().getParent().toFile())
      .redirectErrorStream(true)
      .start();

    // wait for its "waiting for debug protocol on port N" announcement
    BufferedReader stdout = new BufferedReader(
      new InputStreamReader(adapter.getInputStream(), StandardCharsets.UTF_8));
    String line = stdout.readLine();
    System.out.println("[adapter] " + line);
    assertNotNull("adapter announced nothing (died?)", line);
    assertTrue("unexpected announcement: " + line, line.contains("waiting for debug protocol"));

    // keep draining in the background so the adapter can't block on a full pipe
    Thread gobbler = new Thread(() -> {
      try {
        String out;
        while ((out = stdout.readLine()) != null) {
          System.out.println("[adapter] " + out);
        }
      } catch (IOException ignored) {
      }
    }, "adapter-gobbler");
    gobbler.setDaemon(true);
    gobbler.start();

    adapterPort = port;
    client = connectWithRetry(port);
  }

  // WIRE FINDING: the adapter prints its "waiting for debug protocol" line
  // slightly BEFORE the TCP listener accepts, so an immediate connect can be
  // refused - retry briefly (the production launcher must do the same).
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
          throw new IOException("interrupted while connecting to the adapter", ie);
        }
      }
    }
    throw last != null ? last : new IOException("could not connect to the adapter");
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      try {
        client.close();
      } catch (IOException ignored) {
      }
    }
    if (adapter != null) {
      killTree(adapter);
    }
  }

  // Killing node does NOT kill the Firefox it spawned - reap the whole tree,
  // or every probe run leaks a headless browser (live-observed: 122 zombies).
  private static void killTree(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroy();
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(3, TimeUnit.SECONDS);
    }
  }

  @Test(timeout = 30_000)
  public void initializeHandshakeAndCapabilities() throws Exception {
    InitializeRequest initialize = new InitializeRequest();
    InitializeRequestArguments arguments = new InitializeRequestArguments();
    arguments.setClientID("intellij");
    arguments.setAdapterID("firefox");
    // WIRE FINDING: the firefox adapter REJECTS initialize unless
    // pathFormat=="path" ("debug adapter only supports native paths")
    arguments.setPathFormat("path");
    arguments.setLinesStartAt1(true);
    arguments.setColumnsStartAt1(true);
    initialize.setArguments(arguments);

    Response response = client.sendRequest(initialize, TIMEOUT);
    System.out.println("[probe] initialize success=" + response.isSuccess()
                       + " class=" + response.getClass().getSimpleName());
    assertTrue("initialize failed: " + response.getMessage(), response.isSuccess());
    if (response instanceof InitializeResponse init && init.getBody() != null) {
      System.out.println("[probe] capabilities: supportsConfigurationDone="
                         + init.getBody().getSupportsConfigurationDoneRequest()
                         + " supportsSetVariable=" + init.getBody().getSupportsSetVariable()
                         + " supportsConditionalBreakpoints=" + init.getBody().getSupportsConditionalBreakpoints());
    }

    // event-ordering observation: does an initialized event arrive BEFORE any
    // launch (some adapters), or only later? poll briefly and report.
    long deadline = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < deadline) {
      Event event = client.pollEvent(250);
      if (event != null) {
        System.out.println("[probe] event before launch: " + event.getClass().getSimpleName());
      }
    }

    Response disconnect = client.sendRequest(new DisconnectRequest(), TIMEOUT);
    System.out.println("[probe] disconnect success=" + disconnect.isSuccess());
  }

  // ---------------------------------------------------------- full session

  /** A launch request whose arguments are the firefox adapter's own vocabulary. */
  static final class FirefoxLaunchRequest extends Request {
    @SuppressWarnings("unused") // serialized by jackson
    private final Map<String, Object> arguments;

    FirefoxLaunchRequest(Map<String, Object> arguments) {
      setCommand("launch");
      this.arguments = arguments;
    }

    public Map<String, Object> getArguments() {
      return arguments;
    }
  }

  private static Path firefoxExe() {
    for (String candidate : new String[]{
      "C:/Program Files/Mozilla Firefox/firefox.exe",
      "C:/Program Files (x86)/Mozilla Firefox/firefox.exe"}) {
      Path path = Path.of(candidate);
      if (Files.isRegularFile(path)) {
        return path;
      }
    }
    return null;
  }

  private static boolean haxeOnPath() {
    try {
      Process probe = new ProcessBuilder("haxe", "--version").redirectErrorStream(true).start();
      return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  // WebMain.hx line numbers are load-bearing: BP_LINE is `counter++;`
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

  /** Writes + compiles the fixture, returns its directory (app.js/app.js.map/index.html/WebMain.hx). */
  private static Path buildFixture() throws Exception {
    Path dir = Files.createTempDirectory("haxe-web-probe");
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

  @Test(timeout = 120_000)
  public void fullSessionBreakpointInHxSourceViaFileUrl() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path firefox = firefoxExe();
    Assume.assumeTrue("firefox not installed - skipping", firefox != null);
    Path fixture = buildFixture();
    System.out.println("[probe] fixture at " + fixture);

    InitializeRequest initialize = new InitializeRequest();
    InitializeRequestArguments initArgs = new InitializeRequestArguments();
    initArgs.setClientID("intellij");
    initArgs.setAdapterID("firefox");
    initArgs.setPathFormat("path");
    initArgs.setLinesStartAt1(true);
    initArgs.setColumnsStartAt1(true);
    initialize.setArguments(initArgs);
    assertTrue("initialize", client.sendRequest(initialize, TIMEOUT).isSuccess());

    Map<String, Object> launchConfig = new LinkedHashMap<>();
    launchConfig.put("request", "launch");
    launchConfig.put("file", fixture.resolve("index.html").toString());
    launchConfig.put("firefoxExecutable", firefox.toString());
    launchConfig.put("firefoxArgs", List.of("-headless"));
    Response launch = client.sendRequest(new FirefoxLaunchRequest(launchConfig), 60_000);
    System.out.println("[probe] launch success=" + launch.isSuccess()
                       + (launch.isSuccess() ? "" : " message=" + launch.getMessage()));
    assertTrue("launch failed: " + launch.getMessage(), launch.isSuccess());

    // event order observation: wait for the initialized event (post-launch here)
    boolean initialized = false;
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline && !initialized) {
      Event event = client.pollEvent(250);
      if (event != null) {
        System.out.println("[probe] event: " + event.getClass().getSimpleName());
        initialized = event instanceof InitializedEvent;
      }
    }
    assertTrue("no initialized event after launch", initialized);

    // breakpoint by the ORIGINAL .hx path (native absolute path)
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
    Response bpResponse = client.sendRequest(setBreakpoints, TIMEOUT);
    if (bpResponse instanceof SetBreakpointsResponse ok && ok.getBody() != null) {
      for (var b : ok.getBody().getBreakpoints()) {
        System.out.println("[probe] breakpoint verified=" + b.isVerified() + " line=" + b.getLine());
      }
    }
    assertTrue("setBreakpoints failed", bpResponse.isSuccess());

    // the ticking fixture must hit the breakpoint soon
    StoppedEvent stopped = null;
    deadline = System.currentTimeMillis() + 45_000;
    while (System.currentTimeMillis() < deadline && stopped == null) {
      Event event = client.pollEvent(250);
      if (event != null) {
        System.out.println("[probe] event: " + event.getClass().getSimpleName()
                           + (event instanceof StoppedEvent s ? " reason=" + s.getBody().getReason() : ""));
        if (event instanceof StoppedEvent s) {
          stopped = s;
        }
      }
    }
    assertNotNull("breakpoint never hit", stopped);
    int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 1;

    assertTrue("threads", client.sendRequest(new ThreadsRequest(), TIMEOUT).isSuccess());

    StackTraceRequest stackTrace = new StackTraceRequest();
    StackTraceArguments stArgs = new StackTraceArguments();
    stArgs.setThreadId(threadId);
    stackTrace.setArguments(stArgs);
    Response stResponse = client.sendRequest(stackTrace, TIMEOUT);
    assertTrue("stackTrace", stResponse.isSuccess());
    List<StackFrame> frames = ((StackTraceResponse)stResponse).getBody().getStackFrames();
    for (int i = 0; i < Math.min(3, frames.size()); i++) {
      StackFrame frame = frames.get(i);
      System.out.println("[probe] frame " + i + ": " + frame.getName()
                         + " @ " + (frame.getSource() != null ? frame.getSource().getPath() : "?")
                         + ":" + frame.getLine());
    }
    assertTrue("no frames", !frames.isEmpty());
    StackFrame top = frames.get(0);
    assertNotNull("top frame has no source", top.getSource());
    assertTrue("top frame is not the .hx original: " + top.getSource().getPath(),
               top.getSource().getPath() != null && top.getSource().getPath().endsWith("WebMain.hx"));
    assertTrue("wrong line: " + top.getLine(), top.getLine() == BP_LINE);

    // scopes + a few variables of the top frame
    ScopesRequest scopes = new ScopesRequest();
    ScopesArguments scArgs = new ScopesArguments();
    scArgs.setFrameId(top.getId());
    scopes.setArguments(scArgs);
    Response scResponse = client.sendRequest(scopes, TIMEOUT);
    assertTrue("scopes", scResponse.isSuccess());
    for (var scope : ((ScopesResponse)scResponse).getBody().getScopes()) {
      VariablesRequest variables = new VariablesRequest();
      VariablesArguments vArgs = new VariablesArguments();
      vArgs.setVariablesReference(scope.getVariablesReference());
      variables.setArguments(vArgs);
      Response vResponse = client.sendRequest(variables, TIMEOUT);
      if (vResponse instanceof VariablesResponse vars && vars.isSuccess() && vars.getBody() != null) {
        List<Variable> list = vars.getBody().getVariables();
        System.out.println("[probe] scope '" + scope.getName() + "': "
                           + list.stream().limit(5).map(v -> v.getName() + "=" + v.getValue()).toList());
      }
    }

    Response disconnect = client.sendRequest(new DisconnectRequest(), TIMEOUT);
    System.out.println("[probe] disconnect success=" + disconnect.isSuccess());
  }

  /**
   * The SERVE mode the IDE backend uses: the fixture is hosted by our own
   * ContentHttpServer and the browser navigates to the http url (webRoot maps
   * served urls back to the content directory for the source maps). Mirrors
   * BrowserDebugBackend's launch config; a stop must still land in the .hx.
   */
  @Test(timeout = 120_000)
  public void fullSessionBreakpointInHxSourceViaContentServer() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path firefox = firefoxExe();
    Assume.assumeTrue("firefox not installed - skipping", firefox != null);
    Path fixture = buildFixture();

    try (ContentHttpServer content = new ContentHttpServer(fixture)) {
      System.out.println("[probe] serving " + fixture + " at " + content.getBaseUrl());

      InitializeRequest initialize = new InitializeRequest();
      InitializeRequestArguments initArgs = new InitializeRequestArguments();
      initArgs.setClientID("intellij");
      initArgs.setAdapterID("firefox");
      initArgs.setPathFormat("path");
      initArgs.setLinesStartAt1(true);
      initArgs.setColumnsStartAt1(true);
      initialize.setArguments(initArgs);
      assertTrue("initialize", client.sendRequest(initialize, TIMEOUT).isSuccess());

      Map<String, Object> launchConfig = new LinkedHashMap<>();
      launchConfig.put("request", "launch");
      launchConfig.put("url", content.getBaseUrl());
      launchConfig.put("webRoot", fixture.toString());
      launchConfig.put("firefoxExecutable", firefox.toString());
      launchConfig.put("firefoxArgs", List.of("-headless"));
      Response launch = client.sendRequest(new FirefoxLaunchRequest(launchConfig), 60_000);
      assertTrue("launch failed: " + launch.getMessage(), launch.isSuccess());

      boolean initialized = false;
      long deadline = System.currentTimeMillis() + 30_000;
      while (System.currentTimeMillis() < deadline && !initialized) {
        initialized = client.pollEvent(250) instanceof InitializedEvent;
      }
      assertTrue("no initialized event after launch", initialized);

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
      assertTrue("setBreakpoints failed", client.sendRequest(setBreakpoints, TIMEOUT).isSuccess());

      StoppedEvent stopped = null;
      deadline = System.currentTimeMillis() + 45_000;
      while (System.currentTimeMillis() < deadline && stopped == null) {
        if (client.pollEvent(250) instanceof StoppedEvent s) {
          stopped = s;
        }
      }
      assertNotNull("breakpoint never hit over http", stopped);
      int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 1;

      StackTraceRequest stackTrace = new StackTraceRequest();
      StackTraceArguments stArgs = new StackTraceArguments();
      stArgs.setThreadId(threadId);
      stackTrace.setArguments(stArgs);
      Response stResponse = client.sendRequest(stackTrace, TIMEOUT);
      assertTrue("stackTrace", stResponse.isSuccess());
      List<StackFrame> frames = ((StackTraceResponse)stResponse).getBody().getStackFrames();
      assertTrue("no frames", !frames.isEmpty());
      StackFrame top = frames.get(0);
      System.out.println("[probe] http-mode top frame: " + top.getName()
                         + " @ " + (top.getSource() != null ? top.getSource().getPath() : "?")
                         + ":" + top.getLine());
      assertNotNull("top frame has no source", top.getSource());
      assertTrue("top frame is not the .hx original: " + top.getSource().getPath(),
                 top.getSource().getPath() != null && top.getSource().getPath().endsWith("WebMain.hx"));
      assertTrue("wrong line: " + top.getLine(), top.getLine() == BP_LINE);

      Response disconnect = client.sendRequest(new DisconnectRequest(), TIMEOUT);
      System.out.println("[probe] disconnect success=" + disconnect.isSuccess());
    }
  }

  /**
   * The IDE's breakpoint paths come from IntelliJ's VFS, which uses FORWARD
   * slashes on Windows (C:/Users/...). Does the adapter bind those, or only
   * native backslash paths? (The IDE smoke test showed breakpoints never
   * binding; my earlier probes all sent native paths and worked.)
   */
  @Test(timeout = 180_000)
  public void breakpointPathSeparatorSensitivity() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path firefox = firefoxExe();
    Assume.assumeTrue("firefox not installed - skipping", firefox != null);
    Path fixture = buildFixture(); // the ticking fixture: no load race involved

    boolean forwardBound;
    boolean nativeBound;
    try (ContentHttpServer content = new ContentHttpServer(fixture)) {
      forwardBound = runSeparatorVariant("forward", fixture, firefox, content,
                                         fixture.resolve("WebMain.hx").toString().replace('\\', '/'));
    }
    try (ContentHttpServer content = new ContentHttpServer(fixture)) {
      nativeBound = runSeparatorVariant("native", fixture, firefox, content,
                                        fixture.resolve("WebMain.hx").toString());
    }
    System.out.println("[probe] separator sensitivity: forward=" + forwardBound + " native=" + nativeBound);
    assertTrue("native breakpoint path must bind", nativeBound);
    // no assert on forwardBound: this test RECORDS the adapter's behaviour;
    // the IDE-side fix (DapBreakpointManager normalization) covers either way
  }

  private boolean runSeparatorVariant(String label, Path fixture, Path firefox,
                                      ContentHttpServer content, String breakpointPath) throws Exception {
    int ownPort = ThreadLocalRandom.current().nextInt(20000, 60000);
    Process ownAdapter = new ProcessBuilder(nodeExe().toString(), adapterBundle().toString(), "--server=" + ownPort)
      .directory(adapterBundle().getParent().toFile())
      .redirectErrorStream(true)
      .start();
    try {
      DapClient session = connectWithRetry(ownPort);
      try {
        InitializeRequest initialize = new InitializeRequest();
        InitializeRequestArguments initArgs = new InitializeRequestArguments();
        initArgs.setClientID("intellij");
        initArgs.setAdapterID("firefox");
        initArgs.setPathFormat("path");
        initArgs.setLinesStartAt1(true);
        initArgs.setColumnsStartAt1(true);
        initialize.setArguments(initArgs);
        if (!session.sendRequest(initialize, TIMEOUT).isSuccess()) {
          return false;
        }
        Map<String, Object> launchConfig = new LinkedHashMap<>();
        launchConfig.put("request", "launch");
        launchConfig.put("url", content.getBaseUrl());
        launchConfig.put("webRoot", fixture.toString());
        launchConfig.put("firefoxExecutable", firefox.toString());
        launchConfig.put("firefoxArgs", List.of("-headless"));
        if (!session.sendRequest(new FirefoxLaunchRequest(launchConfig), 60_000).isSuccess()) {
          return false;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        boolean initialized = false;
        while (System.currentTimeMillis() < deadline && !initialized) {
          initialized = session.pollEvent(250) instanceof InitializedEvent;
        }
        if (!initialized) {
          return false;
        }
        SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
        SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(breakpointPath);
        source.setName("WebMain.hx");
        bpArgs.setSource(source);
        SourceBreakpoint bp = new SourceBreakpoint();
        bp.setLine(BP_LINE);
        bpArgs.setBreakpoints(List.of(bp));
        setBreakpoints.setArguments(bpArgs);
        session.sendRequest(setBreakpoints, TIMEOUT);

        StoppedEvent stopped = null;
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && stopped == null) {
          Event event = session.pollEvent(250);
          if (event instanceof StoppedEvent s) {
            stopped = s;
          } else if (event instanceof com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.BreakpointEvent be
                     && be.getBody() != null && be.getBody().getBreakpoint() != null) {
            System.out.println("[probe]   " + label + " breakpointEvent verified="
                               + be.getBody().getBreakpoint().isVerified());
          }
        }
        System.out.println("[probe]   " + label + " path stop=" + (stopped != null));
        session.sendRequest(new DisconnectRequest(), TIMEOUT);
        return stopped != null;
      } finally {
        session.close();
      }
    } finally {
      killTree(ownAdapter);
    }
  }

  // ------------------------------------------- load-time breakpoint strategies

  // WebLoad.hx line numbers are load-bearing: LOAD_BP_LINE is `var marker...`,
  // which executes DURING PAGE LOAD - the race the ticking fixture cannot see.
  private static final int LOAD_BP_LINE = 3;
  private static final String WEB_LOAD_HX = """
    class WebLoad {
    	static function main() {
    		var marker = "before"; // LOAD_BP_LINE = 3
    		js.Browser.console.log(marker + "-loaded");
    	}
    }
    """;

  private static Path buildLoadFixture() throws Exception {
    Path dir = Files.createTempDirectory("haxe-web-load-probe");
    Files.writeString(dir.resolve("WebLoad.hx"), WEB_LOAD_HX);
    Files.writeString(dir.resolve("index.html"),
                      "<!DOCTYPE html><html><head><meta charset='utf-8'></head>"
                      + "<body><script src='app.js'></script></body></html>");
    Process haxe = new ProcessBuilder("haxe", "-cp", dir.toString(), "-main", "WebLoad",
                                      "-js", dir.resolve("app.js").toString(), "-debug")
      .redirectErrorStream(true).start();
    String output = new String(haxe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!haxe.waitFor(30, TimeUnit.SECONDS) || haxe.exitValue() != 0) {
      throw new AssertionError("fixture compile failed:\n" + output);
    }
    return dir;
  }

  /**
   * Which strategy beats the load race: code in main() runs while the page
   * loads, likely BEFORE the standard breakpoint flow (launch -> initialized
   * -> setBreakpoints) completes. Tries, in order:
   *   A: breakpoints BEFORE launch;
   *   B: breakpoints before launch + reloadOnAttach:true;
   *   C: standard order + reloadOnAttach:true.
   * Each variant is a fresh DAP connection to the same adapter server (it
   * accepts sequential connections). Asserts at least one variant stops.
   */
  @Test(timeout = 300_000)
  public void loadTimeBreakpointStrategies() throws Exception {
    Assume.assumeTrue("haxe not on PATH - skipping", haxeOnPath());
    Path firefox = firefoxExe();
    Assume.assumeTrue("firefox not installed - skipping", firefox != null);
    Path fixture = buildLoadFixture();

    // J: plain standard flow (baseline: does the adapter attach/emit at all?)
    // K: refresh-once, NO injection (second load hits via the learned map?)
    // I: refresh-once + `debugger;` entry-pause injection
    Path appJs = fixture.resolve("app.js");
    String pristineAppJs = Files.readString(appJs);

    String worked = null;
    for (String variant : new String[]{"J", "K"}) {
      Files.writeString(appJs, variant.equals("I")
                               ? "debugger;" + pristineAppJs : pristineAppJs);
      try (ContentHttpServer content = new ContentHttpServer(fixture)) {
        content.setRequestListener(line -> System.out.println("[server] " + line));
        boolean stopped = runLoadVariant(variant, fixture, firefox, content);
        System.out.println("[probe] load-variant " + variant + ": " + (stopped ? "STOPPED" : "missed"));
        if (stopped && worked == null) {
          worked = variant;
        }
      }
    }
    assertNotNull("no strategy hit the load-time breakpoint", worked);
    System.out.println("[probe] first working load strategy: " + worked);
  }

  private static String jsString(String value) {
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private boolean runLoadVariant(String variant, Path fixture, Path firefox, ContentHttpServer content)
    throws Exception {
    // every variant gets its OWN adapter process + first connection, so no
    // verdict is polluted by session-reuse behaviour of the adapter server
    int ownPort = ThreadLocalRandom.current().nextInt(20000, 60000);
    Process ownAdapter = new ProcessBuilder(nodeExe().toString(), adapterBundle().toString(), "--server=" + ownPort)
      .directory(adapterBundle().getParent().toFile())
      .redirectErrorStream(true)
      .start();
    Thread gobbler = new Thread(() -> {
      try (BufferedReader out = new BufferedReader(
        new InputStreamReader(ownAdapter.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = out.readLine()) != null) {
          System.out.println("[adapter-" + variant + "] " + line);
        }
      } catch (IOException ignored) {
      }
    }, "adapter-gobbler-" + variant);
    gobbler.setDaemon(true);
    gobbler.start();
    try {
      DapClient session = connectWithRetry(ownPort);
      try {
        InitializeRequest initialize = new InitializeRequest();
        InitializeRequestArguments initArgs = new InitializeRequestArguments();
        initArgs.setClientID("intellij");
        initArgs.setAdapterID("firefox");
        initArgs.setPathFormat("path");
        initArgs.setLinesStartAt1(true);
        initArgs.setColumnsStartAt1(true);
        initialize.setArguments(initArgs);
        if (!session.sendRequest(initialize, TIMEOUT).isSuccess()) {
          return false;
        }

        boolean refreshOnce = !variant.equals("J");
        if (refreshOnce) {
          content.refreshFirstPage(2);
        }

        Map<String, Object> launchConfig = new LinkedHashMap<>();
        launchConfig.put("request", "launch");
        launchConfig.put("url", content.getBaseUrl());
        launchConfig.put("webRoot", fixture.toString());
        launchConfig.put("firefoxExecutable", firefox.toString());
        launchConfig.put("firefoxArgs", List.of("-headless"));
        Response launch = session.sendRequest(new FirefoxLaunchRequest(launchConfig), 60_000);
        if (!launch.isSuccess()) {
          System.out.println("[probe]   variant " + variant + " launch failed: " + launch.getMessage());
          return false;
        }

        long deadline = System.currentTimeMillis() + 30_000;
        boolean initialized = false;
        while (System.currentTimeMillis() < deadline && !initialized) {
          initialized = session.pollEvent(250) instanceof InitializedEvent;
        }
        if (!initialized) {
          System.out.println("[probe]   variant " + variant + " no initialized event");
          return false;
        }

        SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
        SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(fixture.resolve("WebLoad.hx").toString());
        source.setName("WebLoad.hx");
        bpArgs.setSource(source);
        SourceBreakpoint bp = new SourceBreakpoint();
        bp.setLine(LOAD_BP_LINE);
        bpArgs.setBreakpoints(List.of(bp));
        setBreakpoints.setArguments(bpArgs);
        Response bpResponse = session.sendRequest(setBreakpoints, TIMEOUT);
        System.out.println("[probe]   variant " + variant + " setBreakpoints success=" + bpResponse.isSuccess());

        // observe everything; an entry pause (non-breakpoint stop) is resumed
        // after a beat so the map can bind; success = a stop ON the .hx line
        deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
          Event event = session.pollEvent(250);
          if (event == null) {
            continue;
          }
          String detail = event instanceof StoppedEvent s ? " reason=" + s.getBody().getReason() : "";
          System.out.println("[probe]   variant " + variant + " event '" + event.getEvent() + "' ("
                             + event.getClass().getSimpleName() + ")" + detail);
          if (!(event instanceof StoppedEvent stopped)) {
            continue;
          }
          int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 1;
          StackTraceRequest stackTrace = new StackTraceRequest();
          StackTraceArguments stArgs = new StackTraceArguments();
          stArgs.setThreadId(threadId);
          stackTrace.setArguments(stArgs);
          Response stResponse = session.sendRequest(stackTrace, TIMEOUT);
          StackFrame top = stResponse instanceof StackTraceResponse st && st.isSuccess()
                           && !st.getBody().getStackFrames().isEmpty()
                           ? st.getBody().getStackFrames().get(0) : null;
          System.out.println("[probe]   variant " + variant + " stop frame: "
                             + (top == null ? "<none>"
                                : (top.getSource() != null ? top.getSource().getPath() : "?") + ":" + top.getLine()));
          boolean onBpLine = top != null && top.getSource() != null && top.getSource().getPath() != null
                             && top.getSource().getPath().endsWith("WebLoad.hx") && top.getLine() == LOAD_BP_LINE;
          if (onBpLine) {
            session.sendRequest(new DisconnectRequest(), TIMEOUT);
            return true;
          }
          // entry pause or foreign stop: give the adapter a beat to bind, resume
          Thread.sleep(1_500);
          ContinueRequest resume = new ContinueRequest();
          ContinueArguments cArgs = new ContinueArguments();
          cArgs.setThreadId(threadId);
          resume.setArguments(cArgs);
          session.sendRequest(resume, TIMEOUT);
        }
        session.sendRequest(new DisconnectRequest(), TIMEOUT);
        return false;
      } finally {
        session.close();
      }
    } finally {
      killTree(ownAdapter);
    }
  }
}
