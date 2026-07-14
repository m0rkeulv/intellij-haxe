package com.intellij.plugins.haxe.runner.debugger.hxcpp.vshaxe.adapter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.LaunchRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetBreakpointsResponse;
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
import org.junit.After;

/**
 * Scaffolding for integration tests against a real debuggee fixture:
 * binds the adapter's listener, wires the DAP loopback pair exactly as the
 * plugin does, spawns the fixture exe, and drains its output for the whole
 * test (an undrained pipe blocks the debuggee once the OS buffer fills).
 * Tests skip when the fixture exe is missing (no haxe/hxcpp toolchain).
 */
abstract class HxcppIntegrationTestBase {
  protected static final long TIMEOUT = 15_000;

  protected HxcppDebugAdapter adapter;
  protected DapClient dapClient;
  protected Process debuggee;
  protected Path fixtureSource;

  private ServerSocket dapListener;
  private final StringBuilder debuggeeOutput = new StringBuilder();

  /**
   * Starts a session around the fixture named by the system property
   * (e.g. {@code hxcpp.fixture.spin.exe}); {@code sourceFile} is the fixture's
   * source under test-fixtures/src for breakpoint-marker lookup.
   */
  protected void launchFixture(String exeProperty, String sourceFile) throws IOException {
    String exeValue = System.getProperty(exeProperty);
    assumeTrue("fixture exe not built (" + exeProperty + "); haxe/hxcpp toolchain missing?",
               exeValue != null && Files.isRegularFile(Path.of(exeValue)));
    Path exe = Path.of(exeValue);
    fixtureSource = Path.of(System.getProperty("hxcpp.fixture.src.dir"), sourceFile);
    int port = Integer.getInteger("hxcpp.fixture.port", 6973);

    // listener must exist before the debuggee starts, or its connect fails
    adapter = new HxcppDebugAdapter("127.0.0.1", port, TIMEOUT);
    dapListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    Socket clientSide = new Socket("127.0.0.1", dapListener.getLocalPort());
    adapter.start(new DapConnection(dapListener.accept()));
    dapClient = new DapClient(new DapConnection(clientSide));

    debuggee = new ProcessBuilder(exe.toString())
      .directory(exe.getParent().toFile())
      .redirectErrorStream(true)
      .start();
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
  public void tearDownSession() throws IOException {
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

  /** initialize (+ initialized event) and launch; the debuggee is then held before main. */
  protected void initializeAndLaunch() throws Exception {
    assertTrue(dapClient.sendRequest(new InitializeRequest(), TIMEOUT).isSuccess());
    awaitEvent(InitializedEvent.class);
    assertTrue("launch failed - did the debuggee connect?",
               dapClient.sendRequest(new LaunchRequest(), TIMEOUT).isSuccess());
  }

  protected Event awaitEvent(Class<? extends Event> type) throws InterruptedException {
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

  protected String output() {
    synchronized (debuggeeOutput) {
      return debuggeeOutput.toString();
    }
  }

  /** Sends the request and fails with the server's error message rather than a cast error. */
  @SuppressWarnings("unchecked")
  protected <T extends Response> T require(Request request) throws Exception {
    Response response = dapClient.sendRequest(request, TIMEOUT);
    assertTrue("'" + request.getCommand() + "' failed: " + response.getMessage(), response.isSuccess());
    return (T)response;
  }

  /** The 1-based line of a "// bp:<marker>" comment in the fixture source. */
  protected int lineOfMarker(String marker) throws IOException {
    List<String> lines = Files.readAllLines(fixtureSource);
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("// bp:" + marker)) {
        return i + 1;
      }
    }
    throw new AssertionError("No '// bp:" + marker + "' marker in " + fixtureSource);
  }

  protected SetBreakpointsResponse setBreakpoints(SourceBreakpoint... breakpoints) throws Exception {
    Source source = new Source();
    // deliberately IDE-shaped (forward slashes, as VirtualFile.getPath()
    // reports on Windows): the adapter must convert before the server's
    // exact-string path matching
    source.setPath(fixtureSource.toString().replace('\\', '/'));
    SetBreakpointsArguments arguments = new SetBreakpointsArguments();
    arguments.setSource(source);
    arguments.setBreakpoints(List.of(breakpoints));
    SetBreakpointsRequest request = new SetBreakpointsRequest();
    request.setArguments(arguments);
    SetBreakpointsResponse response = require(request);
    assertNotNull(response.getBody());
    return response;
  }

  protected SetBreakpointsResponse setBreakpointLines(int... lines) throws Exception {
    SourceBreakpoint[] breakpoints = new SourceBreakpoint[lines.length];
    for (int i = 0; i < lines.length; i++) {
      breakpoints[i] = new SourceBreakpoint();
      breakpoints[i].setLine(lines[i]);
    }
    return setBreakpoints(breakpoints);
  }
}
