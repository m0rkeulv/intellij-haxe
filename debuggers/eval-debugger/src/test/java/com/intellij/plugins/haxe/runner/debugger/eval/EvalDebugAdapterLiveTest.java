package com.intellij.plugins.haxe.runner.debugger.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.TerminatedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequestArguments;
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
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.transport.DapConnection;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link EvalDebugAdapter} end to end: a real {@link DapClient} on one
 * side, the REAL haxe eval VM (spawned with -D eval-debugger) on the other.
 * The full IDE flow — initialize, launch, breakpoints, configurationDone,
 * stop, stack/scopes/variables, evaluate, step, resume, terminate — against
 * the fixture in test-fixtures/EvalMain.hx. Skips when haxe is not on PATH.
 */
public class EvalDebugAdapterLiveTest {
  private static final int BREAK_LINE = 10;
  private static final long TIMEOUT = 15_000;

  private EvalDebugAdapter adapter;
  private DapClient dapClient;
  private ServerSocket dapListener;
  private Process haxe;

  private static boolean haxeOnPath() {
    try {
      Process probe = new ProcessBuilder("haxe", "--version").redirectErrorStream(true).start();
      return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static Path fixtureDir() {
    String fromGradle = System.getProperty("eval.fixture.src.dir");
    return fromGradle != null ? Path.of(fromGradle) : Path.of("test-fixtures").toAbsolutePath();
  }

  @Before
  public void wire() throws IOException {
    Assume.assumeTrue("haxe not on PATH - skipping live eval adapter test", haxeOnPath());
    Path fixtures = fixtureDir();
    Assume.assumeTrue("eval fixture missing - skipping", Files.isRegularFile(fixtures.resolve("EvalMain.hx")));

    adapter = new EvalDebugAdapter(TIMEOUT);
    haxe = new ProcessBuilder("haxe", "-cp", fixtures.toString(), "-main", "EvalMain",
                              "-D", "eval-debugger=127.0.0.1:" + adapter.getVmPort(),
                              "--interp")
      .redirectErrorStream(true)
      .start();

    dapListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    Socket clientSide = new Socket(InetAddress.getLoopbackAddress(), dapListener.getLocalPort());
    Socket adapterSide = dapListener.accept();
    adapter.start(new DapConnection(adapterSide));
    dapClient = new DapClient(new DapConnection(clientSide));
  }

  @After
  public void tearDown() throws Exception {
    if (dapClient != null) {
      try {
        dapClient.close();
      } catch (IOException ignored) {
      }
    }
    if (adapter != null) {
      adapter.close();
    }
    if (haxe != null && !haxe.waitFor(3, TimeUnit.SECONDS)) {
      haxe.descendants().forEach(ProcessHandle::destroyForcibly);
      haxe.destroyForcibly();
      haxe.waitFor(5, TimeUnit.SECONDS);
    }
    if (dapListener != null) {
      dapListener.close();
    }
  }

  private Response request(Request request) throws Exception {
    return dapClient.sendRequest(request, TIMEOUT);
  }

  private StoppedEvent awaitStopped() throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT;
    while (System.currentTimeMillis() < deadline) {
      Event event = dapClient.pollEvent(250);
      if (event instanceof StoppedEvent stopped) {
        return stopped;
      }
    }
    throw new AssertionError("no stopped event within " + TIMEOUT + "ms");
  }

  private boolean awaitTerminated() throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT;
    while (System.currentTimeMillis() < deadline) {
      Event event = dapClient.pollEvent(250);
      if (event instanceof TerminatedEvent) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void fullSessionBreakpointInspectStepAndFinish() throws Exception {
    InitializeRequest initialize = new InitializeRequest();
    InitializeRequestArguments initArgs = new InitializeRequestArguments();
    initArgs.setAdapterID("intellij-haxe-eval");
    initialize.setArguments(initArgs);
    assertTrue("initialize", request(initialize).isSuccess());
    assertNotNull("initialized event", dapClient.pollEvent(TIMEOUT));

    assertTrue("launch (VM connected and waiting)", request(new LaunchRequest()).isSuccess());

    String fixture = fixtureDir().resolve("EvalMain.hx").toString();
    SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
    SetBreakpointsArguments bpArgs = new SetBreakpointsArguments();
    Source source = new Source();
    source.setPath(fixture);
    bpArgs.setSource(source);
    SourceBreakpoint breakpoint = new SourceBreakpoint();
    breakpoint.setLine(BREAK_LINE);
    bpArgs.setBreakpoints(List.of(breakpoint));
    setBreakpoints.setArguments(bpArgs);
    assertTrue("setBreakpoints", request(setBreakpoints).isSuccess());

    assertTrue("configurationDone releases the waiting VM",
               request(new ConfigurationDoneRequest()).isSuccess());

    StoppedEvent stopped = awaitStopped();
    assertEquals("stopped by the breakpoint", "breakpoint", stopped.getBody().getReason());
    int threadId = stopped.getBody().getThreadId();

    assertTrue("threads", request(new ThreadsRequest()).isSuccess());

    StackTraceRequest stackTrace = new StackTraceRequest();
    StackTraceArguments stArgs = new StackTraceArguments();
    stArgs.setThreadId(threadId);
    stackTrace.setArguments(stArgs);
    StackTraceResponse stResponse = (StackTraceResponse)request(stackTrace);
    assertTrue("stackTrace", stResponse.isSuccess());
    List<StackFrame> frames = stResponse.getBody().getStackFrames();
    assertFalse("frames at the stop", frames.isEmpty());
    StackFrame top = frames.get(0);
    assertEquals("stopped on the break line", BREAK_LINE, top.getLine());
    assertNotNull("top frame has a source", top.getSource());
    assertTrue("top frame is the fixture",
               top.getSource().getPath().replace('\\', '/').endsWith("EvalMain.hx"));

    ScopesRequest scopes = new ScopesRequest();
    ScopesArguments scArgs = new ScopesArguments();
    scArgs.setFrameId(top.getId());
    scopes.setArguments(scArgs);
    ScopesResponse scResponse = (ScopesResponse)request(scopes);
    assertTrue("scopes", scResponse.isSuccess());
    assertFalse("scopes present", scResponse.getBody().getScopes().isEmpty());

    boolean sawGreeting = false;
    for (Scope scope : scResponse.getBody().getScopes()) {
      VariablesRequest variables = new VariablesRequest();
      VariablesArguments vArgs = new VariablesArguments();
      vArgs.setVariablesReference(scope.getVariablesReference());
      variables.setArguments(vArgs);
      VariablesResponse vResponse = (VariablesResponse)request(variables);
      assertTrue("variables of scope " + scope.getName(), vResponse.isSuccess());
      for (Variable variable : vResponse.getBody().getVariables()) {
        if ("greeting".equals(variable.getName())) {
          sawGreeting = true;
          assertTrue("greeting holds its value (was " + variable.getValue() + ")",
                     variable.getValue().contains("hello"));
        }
      }
    }
    assertTrue("local 'greeting' visible through DAP", sawGreeting);

    EvaluateRequest evaluate = new EvaluateRequest();
    EvaluateArguments eArgs = new EvaluateArguments();
    eArgs.setExpression("greeting.length + 1");
    eArgs.setFrameId(top.getId());
    evaluate.setArguments(eArgs);
    EvaluateResponse eResponse = (EvaluateResponse)request(evaluate);
    assertTrue("evaluate", eResponse.isSuccess());
    assertEquals("greeting.length + 1 == 6", "6", eResponse.getBody().getResult());

    // step over the break line and land on the next one, still in main
    NextRequest next = new NextRequest();
    NextArguments nArgs = new NextArguments();
    nArgs.setThreadId(threadId);
    next.setArguments(nArgs);
    assertTrue("next", request(next).isSuccess());
    StoppedEvent afterStep = awaitStopped();
    StackTraceResponse stepStack = (StackTraceResponse)request(stackTrace);
    assertEquals("landed on the line after the breakpoint", BREAK_LINE + 1,
                 stepStack.getBody().getStackFrames().get(0).getLine());

    ContinueRequest resume = new ContinueRequest();
    ContinueArguments cArgs = new ContinueArguments();
    cArgs.setThreadId(afterStep.getBody().getThreadId());
    resume.setArguments(cArgs);
    Response resumeResponse = request(resume);
    assertTrue("continue failed: " + resumeResponse.getMessage(), resumeResponse.isSuccess());

    assertTrue("terminated event when the script finishes", awaitTerminated());
    assertTrue("haxe exited", haxe.waitFor(TIMEOUT, TimeUnit.MILLISECONDS));
    assertEquals("clean exit", 0, haxe.exitValue());
  }
}
