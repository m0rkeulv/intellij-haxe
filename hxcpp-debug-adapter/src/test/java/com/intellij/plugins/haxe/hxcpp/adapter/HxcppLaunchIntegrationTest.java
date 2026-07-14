package com.intellij.plugins.haxe.hxcpp.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.TerminatedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.LaunchRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ThreadsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.EvaluateResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetBreakpointsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ThreadsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.transport.DapConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * First contact with a real debuggee: launches the compiled fixture
 * (test-fixtures/src/Main.hx, built by the buildHxcppFixture gradle task)
 * and drives the whole DAP session against the real hxcpp-debug-server.
 * Skips when the fixture exe is missing (no haxe/hxcpp toolchain).
 *
 * The whole lifecycle is one test: an hxcpp session is expensive to start
 * and every stage depends on the previous one anyway.
 */
public class HxcppLaunchIntegrationTest {
  private static final long TIMEOUT = 15_000;

  private HxcppDebugAdapter adapter;
  private DapClient dapClient;
  private ServerSocket dapListener;
  private Process debuggee;
  private final StringBuilder debuggeeOutput = new StringBuilder();

  private Path fixtureExe;
  private Path fixtureSource;

  @Before
  public void setUp() throws IOException {
    String exeProperty = System.getProperty("hxcpp.fixture.exe");
    assumeTrue("hxcpp fixture exe not built (haxe/hxcpp toolchain missing)",
               exeProperty != null && Files.isRegularFile(Path.of(exeProperty)));
    fixtureExe = Path.of(exeProperty);
    fixtureSource = Path.of(System.getProperty("hxcpp.fixture.src.dir"), "Main.hx");
    int port = Integer.getInteger("hxcpp.fixture.port", 6973);

    // listener must exist before the debuggee starts, or its connect fails
    adapter = new HxcppDebugAdapter("127.0.0.1", port, TIMEOUT);
    dapListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    Socket clientSide = new Socket("127.0.0.1", dapListener.getLocalPort());
    adapter.start(new DapConnection(dapListener.accept()));
    dapClient = new DapClient(new DapConnection(clientSide));

    debuggee = new ProcessBuilder(fixtureExe.toString())
      .directory(fixtureExe.getParent().toFile())
      .redirectErrorStream(true)
      .start();
    // drain the pipe for the whole test — an undrained pipe blocks the
    // debuggee mid-write once the OS buffer fills (learned on HashLink)
    Thread gobbler = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(debuggee.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (debuggeeOutput) {
            debuggeeOutput.append(line).append('\n');
          }
        }
      } catch (IOException ignored) {
        // process ended
      }
    }, "debuggee-output-gobbler");
    gobbler.setDaemon(true);
    gobbler.start();
  }

  @After
  public void tearDown() throws IOException {
    if (debuggee != null && debuggee.isAlive()) {
      debuggee.destroyForcibly();
    }
    if (dapClient != null) {
      dapClient.close();
    }
    if (adapter != null) {
      adapter.close();
    }
    if (dapListener != null) {
      dapListener.close();
    }
  }

  private Event awaitEvent(Class<? extends Event> type) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT;
    while (System.currentTimeMillis() < deadline) {
      Event event = dapClient.pollEvent(250);
      if (event != null && type.isInstance(event)) {
        return event;
      }
    }
    throw new AssertionError("No " + type.getSimpleName() + " within " + TIMEOUT + " ms; debuggee output so far:\n"
                             + output());
  }

  private String output() {
    synchronized (debuggeeOutput) {
      return debuggeeOutput.toString();
    }
  }

  /** Sends the request and fails with the server's error message rather than a cast error. */
  @SuppressWarnings("unchecked")
  private <T extends com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response> T require(
    com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request request) throws Exception {
    com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response response =
      dapClient.sendRequest(request, TIMEOUT);
    assertTrue("'" + request.getCommand() + "' failed: " + response.getMessage(), response.isSuccess());
    return (T)response;
  }

  /** The 1-based line of a "// bp:<marker>" comment in the fixture source. */
  private int lineOfMarker(String marker) throws IOException {
    List<String> lines = Files.readAllLines(fixtureSource);
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("// bp:" + marker)) {
        return i + 1;
      }
    }
    throw new AssertionError("No '// bp:" + marker + "' marker in " + fixtureSource);
  }

  private SetBreakpointsResponse setBreakpoints(int... lines) throws Exception {
    Source source = new Source();
    // deliberately IDE-shaped (forward slashes, as VirtualFile.getPath()
    // reports on Windows): the adapter must convert before the server's
    // exact-string path matching — a real breakpoint stop below proves it
    source.setPath(fixtureSource.toString().replace('\\', '/'));
    SetBreakpointsArguments arguments = new SetBreakpointsArguments();
    arguments.setSource(source);
    arguments.setBreakpoints(java.util.Arrays.stream(lines).mapToObj(line -> {
      SourceBreakpoint breakpoint = new SourceBreakpoint();
      breakpoint.setLine(line);
      return breakpoint;
    }).toList());
    SetBreakpointsRequest request = new SetBreakpointsRequest();
    request.setArguments(arguments);
    SetBreakpointsResponse response = (SetBreakpointsResponse)dapClient.sendRequest(request, TIMEOUT);
    assertTrue("setBreakpoints failed: " + response.getMessage(), response.isSuccess());
    return response;
  }

  @Test
  public void fullDebugLifecycleAgainstTheRealServer() throws Exception {
    // --- initialize + launch ------------------------------------------------
    assertTrue(dapClient.sendRequest(new InitializeRequest(), TIMEOUT).isSuccess());
    awaitEvent(InitializedEvent.class);
    assertTrue("launch failed - did the debuggee connect?",
               dapClient.sendRequest(new LaunchRequest(), TIMEOUT).isSuccess());

    // --- breakpoint before the program runs --------------------------------
    int accumulateLine = lineOfMarker("accumulate");
    SetBreakpointsResponse breakpoints = setBreakpoints(accumulateLine);
    assertEquals(1, breakpoints.getBody().getBreakpoints().size());
    assertTrue(breakpoints.getBody().getBreakpoints().get(0).isVerified());

    // the program is held before main until configurationDone continues it
    assertTrue(dapClient.sendRequest(new ConfigurationDoneRequest(), TIMEOUT).isSuccess());

    // --- first hit ----------------------------------------------------------
    StoppedEvent stopped = (StoppedEvent)awaitEvent(StoppedEvent.class);
    int threadId = stopped.getBody().getThreadId();

    ThreadsResponse threads = require(new ThreadsRequest());
    assertFalse(threads.getBody().getThreads().isEmpty());

    StackTraceArguments stackArguments = new StackTraceArguments();
    stackArguments.setThreadId(threadId);
    StackTraceRequest stackRequest = new StackTraceRequest();
    stackRequest.setArguments(stackArguments);
    StackTraceResponse stack = require(stackRequest);
    List<StackFrame> frames = stack.getBody().getStackFrames();
    assertFalse("no stack frames", frames.isEmpty());
    StackFrame top = frames.get(0);
    assertTrue("top frame is '" + top.getName() + "', expected accumulate",
               top.getName().contains("accumulate"));
    assertEquals(accumulateLine, top.getLine());
    assertNotNull(top.getSource());
    assertTrue(top.getSource().getPath().endsWith("Main.hx"));

    // --- scopes + variables: known first-iteration values -------------------
    ScopesArguments scopesArguments = new ScopesArguments();
    scopesArguments.setFrameId(top.getId());
    ScopesRequest scopesRequest = new ScopesRequest();
    scopesRequest.setArguments(scopesArguments);
    ScopesResponse scopes = require(scopesRequest);
    assertFalse("no scopes", scopes.getBody().getScopes().isEmpty());

    Variable doubled = null;
    for (Scope scope : scopes.getBody().getScopes()) {
      VariablesArguments variablesArguments = new VariablesArguments();
      variablesArguments.setVariablesReference(scope.getVariablesReference());
      VariablesRequest variablesRequest = new VariablesRequest();
      variablesRequest.setArguments(variablesArguments);
      VariablesResponse variables = require(variablesRequest);
      for (Variable variable : variables.getBody().getVariables()) {
        if ("doubled".equals(variable.getName())) {
          doubled = variable;
        }
      }
    }
    assertNotNull("local 'doubled' not found in any scope", doubled);
    // first iteration: v = items[0] = 0, doubled = 0
    assertEquals("0", doubled.getValue().trim());

    // --- evaluate in the stopped frame --------------------------------------
    EvaluateArguments evaluateArguments = new EvaluateArguments();
    evaluateArguments.setExpression("acc");
    evaluateArguments.setFrameId(top.getId());
    EvaluateRequest evaluateRequest = new EvaluateRequest();
    evaluateRequest.setArguments(evaluateArguments);
    EvaluateResponse evaluate = require(evaluateRequest);
    assertEquals("0", evaluate.getBody().getResult().trim());

    // --- assignment through evaluate must WRITE (the n = 100 bug) -----------
    // a TOP-frame local is writable; the changed value is verified by
    // read-back here and by the program's own trace output at the end
    // (doubled = 55 in iteration 1 makes the final total 115, not 60)
    EvaluateArguments assignArguments = new EvaluateArguments();
    assignArguments.setExpression("doubled = 55");
    assignArguments.setFrameId(top.getId());
    EvaluateRequest assignRequest = new EvaluateRequest();
    assignRequest.setArguments(assignArguments);
    require(assignRequest);

    EvaluateArguments readBackArguments = new EvaluateArguments();
    readBackArguments.setExpression("doubled");
    readBackArguments.setFrameId(top.getId());
    EvaluateRequest readBackRequest = new EvaluateRequest();
    readBackRequest.setArguments(readBackArguments);
    EvaluateResponse readBack = require(readBackRequest);
    assertEquals("assignment did not stick", "55", readBack.getBody().getResult().trim());

    // a CALLER-frame variable is not writable (the server hardcodes the top
    // frame and would silently ignore it) — the adapter must say so
    assertTrue("expected a caller frame", frames.size() >= 2);
    EvaluateArguments callerAssignArguments = new EvaluateArguments();
    callerAssignArguments.setExpression("n = 100");
    callerAssignArguments.setFrameId(frames.get(1).getId());
    EvaluateRequest callerAssignRequest = new EvaluateRequest();
    callerAssignRequest.setArguments(callerAssignArguments);
    com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response callerAssign =
      dapClient.sendRequest(callerAssignRequest, TIMEOUT);
    assertFalse("caller-frame write should be refused, not silently ignored", callerAssign.isSuccess());
    assertTrue(callerAssign.getMessage(), callerAssign.getMessage().contains("TOP stack frame"));

    // --- step over stays in the program -------------------------------------
    NextArguments nextArguments = new NextArguments();
    nextArguments.setThreadId(threadId);
    NextRequest nextRequest = new NextRequest();
    nextRequest.setArguments(nextArguments);
    assertTrue(dapClient.sendRequest(nextRequest, TIMEOUT).isSuccess());
    awaitEvent(StoppedEvent.class);

    // --- second breakpoint hit on continue -----------------------------------
    ContinueArguments continueArguments = new ContinueArguments();
    continueArguments.setThreadId(threadId);
    ContinueRequest continueRequest = new ContinueRequest();
    continueRequest.setArguments(continueArguments);
    assertTrue(dapClient.sendRequest(continueRequest, TIMEOUT).isSuccess());
    awaitEvent(StoppedEvent.class);

    // --- run-to-cursor mechanism: replace the file's set with the target ----
    // (the breakpoint manager sends existing breakpoints + the temp line; here
    // the existing set is empty so only the target remains — the pure case)
    int doneLine = lineOfMarker("done");
    setBreakpoints(doneLine);
    assertTrue(dapClient.sendRequest(continueRequest, TIMEOUT).isSuccess());
    StoppedEvent runToStop = (StoppedEvent)awaitEvent(StoppedEvent.class);
    StackTraceArguments runToStackArguments = new StackTraceArguments();
    runToStackArguments.setThreadId(runToStop.getBody().getThreadId());
    StackTraceRequest runToStackRequest = new StackTraceRequest();
    runToStackRequest.setArguments(runToStackArguments);
    StackTraceResponse runToStack = require(runToStackRequest);
    assertEquals("run-to target line not reached",
                 doneLine, runToStack.getBody().getStackFrames().get(0).getLine());

    // --- clear breakpoints, run to completion --------------------------------
    setBreakpoints(/* none */);
    assertTrue(dapClient.sendRequest(continueRequest, TIMEOUT).isSuccess());

    awaitEvent(TerminatedEvent.class);
    assertTrue("debuggee did not exit", debuggee.waitFor(TIMEOUT, TimeUnit.MILLISECONDS));
    assertEquals("debuggee output:\n" + output(), 0, debuggee.exitValue());
    // 115, not 60: the doubled = 55 write in iteration 1 flowed into the sum —
    // execution-level proof that evaluate assignments reach the debuggee
    assertTrue("expected trace output, got:\n" + output(),
               output().contains("total=115 title=fixture"));
  }
}
