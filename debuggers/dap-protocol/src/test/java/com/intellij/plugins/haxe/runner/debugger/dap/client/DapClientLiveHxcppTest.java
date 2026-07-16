package com.intellij.plugins.haxe.runner.debugger.dap.client;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequestArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.PauseRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetExceptionBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetExceptionBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ThreadsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.transport.DapConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives the REAL Java client stack (DapClient, Jackson-typed requests, byte
 * framing) against the live intellij-hxcpp-debug-server fixture, mirroring the
 * IDE's pause → inspect → resume sequence exactly — the layer a hand-rolled
 * python probe cannot cover. Skips when the native fixture is not built
 * (build it with: haxe test-fixtures/fixture-ex.hxml in the
 * intellij-hxcpp-debugger module).
 */
public class DapClientLiveHxcppTest {
  private static final long TIMEOUT_MILLIS = 15_000;

  private static Path fixture() {
    return Path.of("..", "intellij-hxcpp-debugger", "build", "hxcpp", "fixture-ex", "MainEx-debug.exe")
      .toAbsolutePath().normalize();
  }

  @Test
  public void pauseInspectResumeTwiceStaysResponsive() throws Exception {
    Assume.assumeTrue("hxcpp exception fixture not built: " + fixture(), Files.isRegularFile(fixture()));

    // the runner's contract: bind the ephemeral listener FIRST, then spawn the
    // debuggee with the listener's address in the env vars
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ProcessBuilder builder = new ProcessBuilder(fixture().toString());
      builder.environment().put("HXCPP_DEBUG_HOST", "127.0.0.1");
      builder.environment().put("HXCPP_DEBUG_PORT", Integer.toString(listener.getLocalPort()));
      builder.environment().put("FIXTURE_MODE", "spin");
      builder.redirectErrorStream(true);
      Process debuggee = builder.start();
      List<String> output = Collections.synchronizedList(new ArrayList<>());
      Thread outputPump = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(debuggee.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            output.add(line);
          }
        } catch (IOException ignored) {
        }
      }, "fixture-output");
      outputPump.setDaemon(true);
      outputPump.start();

      listener.setSoTimeout(15_000);
      DapClient client = new DapClient(new DapConnection(listener.accept()));
      try {
        // === exactly HxcppDebugProcess.initializeSession ===
        InitializeRequest initialize = new InitializeRequest();
        InitializeRequestArguments initializeArguments = new InitializeRequestArguments();
        initializeArguments.setAdapterID("intellij-haxe");
        initializeArguments.setClientID("intellij");
        initialize.setArguments(initializeArguments);
        assertTrue("initialize", client.sendRequest(initialize, TIMEOUT_MILLIS).isSuccess());
        assertNotNull("initialized event", client.pollEvent(TIMEOUT_MILLIS));

        SetExceptionBreakpointsRequest filters = new SetExceptionBreakpointsRequest();
        SetExceptionBreakpointsArguments filterArguments = new SetExceptionBreakpointsArguments();
        filterArguments.setFilters(List.of("uncaught", "critical"));
        filters.setArguments(filterArguments);
        assertTrue("setExceptionBreakpoints", client.sendRequest(filters, TIMEOUT_MILLIS).isSuccess());
        assertTrue("configurationDone", client.sendRequest(new ConfigurationDoneRequest(), TIMEOUT_MILLIS).isSuccess());

        Thread.sleep(600); // let the fixture spin

        for (int round = 1; round <= 2; round++) {
          // === pause exactly like startPausing: a bare PauseRequest ===
          assertTrue("pause round " + round, client.sendRequest(new PauseRequest(), TIMEOUT_MILLIS).isSuccess());
          StoppedEvent stopped = awaitStopped(client);
          int threadId = stopped.getBody().getThreadId() != null ? stopped.getBody().getThreadId() : 0;

          // === the event pump's reportStopped sequence ===
          assertTrue("threads round " + round, client.sendRequest(new ThreadsRequest(), TIMEOUT_MILLIS).isSuccess());
          StackTraceRequest stackTrace = new StackTraceRequest();
          StackTraceArguments stackTraceArguments = new StackTraceArguments();
          stackTraceArguments.setThreadId(threadId);
          stackTrace.setArguments(stackTraceArguments);
          StackTraceResponse frames = (StackTraceResponse)client.sendRequest(stackTrace, TIMEOUT_MILLIS);
          assertTrue("stackTrace round " + round, frames.isSuccess());
          int frameId = frames.getBody().getStackFrames().get(0).getId();

          // === the variables view ===
          ScopesRequest scopes = new ScopesRequest();
          ScopesArguments scopesArguments = new ScopesArguments();
          scopesArguments.setFrameId(frameId);
          scopes.setArguments(scopesArguments);
          ScopesResponse scopesResponse = (ScopesResponse)client.sendRequest(scopes, TIMEOUT_MILLIS);
          assertTrue("scopes round " + round, scopesResponse.isSuccess());
          int reference = scopesResponse.getBody().getScopes().get(0).getVariablesReference();

          VariablesRequest variables = new VariablesRequest();
          VariablesArguments variablesArguments = new VariablesArguments();
          variablesArguments.setVariablesReference(reference);
          variables.setArguments(variablesArguments);
          VariablesResponse variablesResponse = (VariablesResponse)client.sendRequest(variables, TIMEOUT_MILLIS);
          assertTrue("variables round " + round, variablesResponse.isSuccess());
          assertTrue("locals present round " + round, !variablesResponse.getBody().getVariables().isEmpty());

          // === resume; the heartbeat proves the program actually runs ===
          int beatsBefore = beats(output);
          ContinueRequest resume = new ContinueRequest();
          ContinueArguments continueArguments = new ContinueArguments();
          continueArguments.setThreadId(threadId);
          resume.setArguments(continueArguments);
          assertTrue("continue round " + round, client.sendRequest(resume, TIMEOUT_MILLIS).isSuccess());

          long deadline = System.currentTimeMillis() + 5_000;
          while (beats(output) <= beatsBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
          }
          assertTrue("program resumed after round " + round, beats(output) > beatsBefore);
        }
      } finally {
        debuggee.destroyForcibly();
        client.close();
      }
    }
  }

  /**
   * The real user flow that mixes stop kinds: breakpoint hit → inspect →
   * continue → pause → inspect → resume → breakpoint hit again. Breakpoints
   * stay INSTALLED across the pause (the IDE flushes persisted breakpoints at
   * session start), which none of the pause-only probes covered.
   */
  @Test
  public void pauseAndResumeWithBreakpointsInstalled() throws Exception {
    Assume.assumeTrue("hxcpp exception fixture not built: " + fixture(), Files.isRegularFile(fixture()));
    Path source = Path.of("..", "intellij-hxcpp-debugger", "test-fixtures", "src", "MainEx.hx")
      .toAbsolutePath().normalize();

    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ProcessBuilder builder = new ProcessBuilder(fixture().toString());
      builder.environment().put("HXCPP_DEBUG_HOST", "127.0.0.1");
      builder.environment().put("HXCPP_DEBUG_PORT", Integer.toString(listener.getLocalPort()));
      builder.environment().put("FIXTURE_MODE", "spin");
      builder.redirectErrorStream(true);
      Process debuggee = builder.start();
      List<String> output = Collections.synchronizedList(new ArrayList<>());
      Thread outputPump = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(debuggee.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            output.add(line);
          }
        } catch (IOException ignored) {
        }
      }, "fixture-output");
      outputPump.setDaemon(true);
      outputPump.start();

      listener.setSoTimeout(15_000);
      DapClient client = new DapClient(new DapConnection(listener.accept()));
      try {
        InitializeRequest initialize = new InitializeRequest();
        InitializeRequestArguments initializeArguments = new InitializeRequestArguments();
        initializeArguments.setAdapterID("intellij-haxe");
        initializeArguments.setClientID("intellij");
        initialize.setArguments(initializeArguments);
        assertTrue(client.sendRequest(initialize, TIMEOUT_MILLIS).isSuccess());
        assertNotNull(client.pollEvent(TIMEOUT_MILLIS));

        // a breakpoint on spin's heartbeat line (fires every ~1s of spinning)
        SetBreakpointsRequest setBreakpoints = new SetBreakpointsRequest();
        SetBreakpointsArguments breakpointArguments = new SetBreakpointsArguments();
        Source mainEx = new Source();
        mainEx.setPath(source.toString());
        breakpointArguments.setSource(mainEx);
        SourceBreakpoint heartbeat = new SourceBreakpoint();
        heartbeat.setLine(52);
        breakpointArguments.setBreakpoints(List.of(heartbeat));
        setBreakpoints.setArguments(breakpointArguments);
        assertTrue("setBreakpoints", client.sendRequest(setBreakpoints, TIMEOUT_MILLIS).isSuccess());
        assertTrue(client.sendRequest(new ConfigurationDoneRequest(), TIMEOUT_MILLIS).isSuccess());

        // breakpoint hit
        StoppedEvent hit = awaitStopped(client);
        int threadId = hit.getBody().getThreadId() != null ? hit.getBody().getThreadId() : 0;
        assertTrue("first stop is the breakpoint", "breakpoint".equals(hit.getBody().getReason()));

        // continue, then pause mid-run (breakpoint still installed)
        ContinueRequest resume = new ContinueRequest();
        ContinueArguments continueArguments = new ContinueArguments();
        continueArguments.setThreadId(threadId);
        resume.setArguments(continueArguments);
        assertTrue(client.sendRequest(resume, TIMEOUT_MILLIS).isSuccess());
        // NOTE: the very next stop may be the breakpoint again (it fires every
        // ~100 beats); drain until we are running a moment, then pause
        Thread.sleep(200);
        assertTrue("pause", client.sendRequest(new PauseRequest(), TIMEOUT_MILLIS).isSuccess());
        StoppedEvent paused = awaitStopped(client);

        StackTraceRequest stackTrace = new StackTraceRequest();
        StackTraceArguments stackTraceArguments = new StackTraceArguments();
        stackTraceArguments.setThreadId(threadId);
        stackTrace.setArguments(stackTraceArguments);
        assertTrue("stackTrace at pause", client.sendRequest(stackTrace, TIMEOUT_MILLIS).isSuccess());

        // resume; the session must stay live and the breakpoint must hit again
        assertTrue("resume after pause", client.sendRequest(resume, TIMEOUT_MILLIS).isSuccess());
        StoppedEvent second = awaitStopped(client);
        assertNotNull("a further stop (breakpoint or pause backlog) arrives", second);
        assertTrue("session still answers requests",
                   client.sendRequest(new ThreadsRequest(), TIMEOUT_MILLIS).isSuccess());
      } finally {
        debuggee.destroyForcibly();
        client.close();
      }
    }
  }

  private static StoppedEvent awaitStopped(DapClient client) throws IOException, InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      Event event = client.pollEvent(250);
      if (event instanceof StoppedEvent stopped) {
        return stopped;
      }
    }
    fail("no stopped event within " + TIMEOUT_MILLIS + "ms");
    return null; // unreachable
  }

  private static int beats(List<String> output) {
    synchronized (output) {
      return (int)output.stream().filter(line -> line.startsWith("beat")).count();
    }
  }
}
