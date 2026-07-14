package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.vshaxe.adapter.HxcppDebugAdapter;
import com.intellij.plugins.haxe.runner.debugger.HaxeBreakpointType;
import com.intellij.plugins.haxe.runner.debugger.HaxeDebuggerEditorsProvider;
import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.DapThread;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.ContinuedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.TerminatedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.DisconnectRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequestArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.LaunchRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.PauseRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetVariableArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetVariableRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepOutArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepOutRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ThreadsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetVariableResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ThreadsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.transport.DapConnection;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * XDebugger process for HXCPP (experimental): drives the in-process
 * {@link HxcppDebugAdapter} as a DAP client and bridges its events into the
 * IDE. Mirrors the HashLink debug process, but simpler in two ways: the
 * adapter is a Java object connected over a loopback socket pair (no external
 * process to manage), and the debuggee is a plain child process (no OS-level
 * debug attachment, so killing it needs no special ceremony).
 *
 * The debuggee is spawned by {@link HxcppDebugRunner}; its {@link
 * ProcessHandler} is the session's process handler, so console output, stdin
 * and the exit code flow through the normal run machinery, and the session
 * ends when the debuggee does.
 *
 * Threading: the IDE calls resume/step/stop on the EDT — those only submit
 * work to a single-thread request executor. A dedicated event-pump thread is
 * the sole {@code pollEvent} caller; DapClient correlates concurrent requests
 * by seq.
 */
public class HxcppDebugProcess extends XDebugProcess {
  private static final Logger LOG = Logger.getInstance(HxcppDebugProcess.class);
  private static final long REQUEST_TIMEOUT_MILLIS = 15_000;
  private static final long DISCONNECT_TIMEOUT_MILLIS = 3_000;
  private static final long EVENT_POLL_MILLIS = 250;

  private final HxcppDebugAdapter adapter;
  private final ProcessHandler processHandler;
  private final HxcppBreakpointManager breakpoints = new HxcppBreakpointManager(this);
  private final ExecutorService requestExecutor =
    Executors.newSingleThreadExecutor(r -> daemon(r, "HXCPP DAP requests"));

  private volatile DapClient client;
  private volatile ServerSocket dapListener;
  private volatile int currentThreadId = 0;
  private volatile boolean shuttingDown = false;
  private volatile boolean launched = false;

  public HxcppDebugProcess(@NotNull XDebugSession session,
                           HxcppDebugAdapter adapter, ProcessHandler debuggeeHandler) {
    super(session);
    this.adapter = adapter;
    this.processHandler = debuggeeHandler;
    // A debuggee dying BEFORE the session is up is always a startup failure
    // (not compiled with the debug server, or its port is poisoned by a
    // leftover instance) — fail immediately with the exit code instead of
    // letting the launch request run into its timeout.
    processHandler.addProcessListener(new ProcessListener() {
      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        if (!shuttingDown && !launched) {
          fail("The program exited (code " + event.getExitCode() + ") before the debugger could attach.\n"
               + "Check that it was compiled with -debug and -lib hxcpp-debug-server, and that no previous\n"
               + "instance of the program is still running (a leftover instance blocks the debug port and\n"
               + "makes new ones crash on startup).");
        }
      }
    });
  }

  // --- lifecycle ---

  @Override
  public void sessionInitialized() {
    getSession().setPauseActionSupported(true);
    requestExecutor.execute(this::initializeSession);
  }

  // The default XDebugProcess.createConsole() builds a console but never
  // attaches it to the process handler (unlike CommandLineState, which does) —
  // without this override the debuggee's stdout/stderr go nowhere.
  @Override
  public @NotNull ExecutionConsole createConsole() {
    ConsoleView console = TextConsoleBuilderFactory.getInstance()
      .createBuilder(getSession().getProject()).getConsole();
    console.attachToProcess(processHandler);
    return console;
  }

  private void initializeSession() {
    try {
      // the adapter lives in-process; the DAP conversation still runs over a
      // real (loopback) socket pair so this side is exactly a DAP client
      ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      dapListener = listener;
      Socket clientSide = new Socket("127.0.0.1", listener.getLocalPort());
      adapter.start(new DapConnection(listener.accept()));
      client = new DapClient(new DapConnection(clientSide));

      InitializeRequest initialize = new InitializeRequest();
      InitializeRequestArguments initializeArguments = new InitializeRequestArguments();
      initializeArguments.setAdapterID("intellij-haxe");
      initializeArguments.setClientID("intellij");
      initialize.setArguments(initializeArguments);
      client.sendRequest(initialize, REQUEST_TIMEOUT_MILLIS);
      client.pollEvent(REQUEST_TIMEOUT_MILLIS); // the initialized event

      // launch = "the debuggee's server connected"; it is held before main
      Response launchResponse = client.sendRequest(new LaunchRequest(), REQUEST_TIMEOUT_MILLIS);
      if (!launchResponse.isSuccess()) {
        fail("Cannot start the HXCPP debug session: " + launchResponse.getMessage());
        return;
      }
      launched = true;

      breakpoints.flushAll();
      // releases the debuggee held by the server's startup break
      client.sendRequest(new ConfigurationDoneRequest(), REQUEST_TIMEOUT_MILLIS);

      Thread pump = daemon(this::pumpEvents, "HXCPP DAP events");
      pump.start();
    } catch (IOException e) {
      fail("Cannot start the HXCPP debug session: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // --- event pump (sole pollEvent caller) ---

  private void pumpEvents() {
    try {
      while (!shuttingDown) {
        Event event = client.pollEvent(EVENT_POLL_MILLIS);
        switch (event) {
          case null -> { /* poll again */ }
          case StoppedEvent stopped -> handleStopped(stopped);
          case ContinuedEvent ignored -> getSession().sessionResumed();
          case TerminatedEvent ignored -> {
            terminateSession();
            return;
          }
          default -> { /* thread start/exit etc.: the views refresh on the next stop */ }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.warn("HXCPP event pump failed", e);
      terminateSession();
    }
  }

  private void handleStopped(StoppedEvent stopped) {
    Integer threadId = stopped.getBody().getThreadId();
    currentThreadId = threadId != null ? threadId : 0;
    String exceptionText = null;
    if ("exception".equals(stopped.getBody().getReason())) {
      String description = stopped.getBody().getDescription();
      exceptionText = description != null ? description : "Exception thrown";
    }
    reportStopped(currentThreadId, exceptionText);
    // run-to-cursor is one-shot: any stop (including a breakpoint reached before
    // the cursor) cancels a pending run-to breakpoint
    breakpoints.clearRunToBreakpoint();
  }

  // Reports the current stop to the session (all threads suspended, the given one active).
  private void reportStopped(int threadId, String exceptionText) {
    List<DapThread> threads = requestThreads();
    List<StackFrame> activeFrames = requestStackTrace(threadId);
    getSession().positionReached(
      new HxcppSuspendContext(this, threads, threadId, activeFrames, exceptionText));
  }

  List<DapThread> requestThreads() {
    return sendRequest(new ThreadsRequest()) instanceof ThreadsResponse response && response.isSuccess()
           ? response.getBody().getThreads() : List.of();
  }

  List<StackFrame> requestStackTrace(int threadId) {
    StackTraceRequest request = new StackTraceRequest();
    StackTraceArguments arguments = new StackTraceArguments();
    arguments.setThreadId(threadId);
    request.setArguments(arguments);
    return sendRequest(request) instanceof StackTraceResponse response && response.isSuccess()
           ? response.getBody().getStackFrames() : List.of();
  }

  private void print(String text, boolean stderr) {
    ConsoleView console = getSession().getConsoleView();
    if (console != null) {
      console.print(text, stderr ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
    } else {
      // startup failures can precede the console; the process handler's
      // listeners (the Console tab once built) still deliver the text
      processHandler.notifyTextAvailable(text, stderr ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT);
    }
  }

  private void fail(String message) {
    print(message + "\n", true);
    terminateSession();
  }

  private void terminateSession() {
    teardown();
    getSession().stop();
  }

  // --- XDebugProcess callbacks (EDT: only submit, never block) ---

  @Override
  public void resume(@Nullable XSuspendContext context) {
    ContinueRequest request = new ContinueRequest();
    ContinueArguments arguments = new ContinueArguments();
    arguments.setThreadId(currentThreadId);
    request.setArguments(arguments);
    onRequestThread(() -> sendRequest(request));
  }

  // Run to cursor: plant a transient breakpoint at the target line and resume.
  // Any stop clears it (handleStopped). If the line has no executable code,
  // don't resume — re-assert the current position so the UI leaves "running".
  @Override
  public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
    String path = position.getFile().getPath();
    int line = position.getLine() + 1; // XSourcePosition is 0-based; DAP is 1-based
    int threadId = currentThreadId;
    onRequestThread(() -> {
      if (breakpoints.setRunToBreakpoint(path, line)) {
        ContinueRequest request = new ContinueRequest();
        ContinueArguments arguments = new ContinueArguments();
        arguments.setThreadId(threadId);
        request.setArguments(arguments);
        sendRequest(request);
      } else {
        breakpoints.clearRunToBreakpoint();
        reportStopped(threadId, null);
      }
    });
  }

  @Override
  public void startPausing() {
    // the server interrupts the debuggee and reports a pauseStop notification,
    // which arrives here as a stopped(reason:"pause") event
    onRequestThread(() -> sendRequest(new PauseRequest()));
  }

  @Override
  public void startStepOver(@Nullable XSuspendContext context) {
    NextRequest request = new NextRequest();
    NextArguments arguments = new NextArguments();
    arguments.setThreadId(currentThreadId);
    request.setArguments(arguments);
    onRequestThread(() -> sendRequest(request));
  }

  @Override
  public void startStepInto(@Nullable XSuspendContext context) {
    StepInRequest request = new StepInRequest();
    StepInArguments arguments = new StepInArguments();
    arguments.setThreadId(currentThreadId);
    request.setArguments(arguments);
    onRequestThread(() -> sendRequest(request));
  }

  @Override
  public void startStepOut(@Nullable XSuspendContext context) {
    StepOutRequest request = new StepOutRequest();
    StepOutArguments arguments = new StepOutArguments();
    arguments.setThreadId(currentThreadId);
    request.setArguments(arguments);
    onRequestThread(() -> sendRequest(request));
  }

  @Override
  public void stop() {
    shuttingDown = true;
    requestExecutor.execute(() -> {
      DapClient dapClient = client;
      if (dapClient != null) {
        try {
          dapClient.sendRequest(new DisconnectRequest(), DISCONNECT_TIMEOUT_MILLIS);
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        }
      }
      teardown();
    });
    requestExecutor.shutdown();
  }

  private synchronized void teardown() {
    shuttingDown = true;
    DapClient dapClient = client;
    client = null;
    if (dapClient != null) {
      try {
        dapClient.close();
      } catch (IOException ignored) {
      }
    }
    try {
      adapter.close();
    } catch (IOException ignored) {
    }
    ServerSocket listener = dapListener;
    dapListener = null;
    if (listener != null) {
      try {
        listener.close();
      } catch (IOException ignored) {
      }
    }
    if (!processHandler.isProcessTerminated()) {
      processHandler.destroyProcess();
    }
  }

  // --- plumbing for breakpoints/frames/values ---

  /** Runs work on the single-thread DAP request executor. */
  void onRequestThread(Runnable work) {
    if (!requestExecutor.isShutdown()) {
      requestExecutor.execute(work);
    }
  }

  /** Blocking request; only call on the request executor or the event pump. */
  @Nullable Response sendRequest(Request request) {
    DapClient dapClient = client;
    if (dapClient == null) {
      return null;
    }
    try {
      return dapClient.sendRequest(request, REQUEST_TIMEOUT_MILLIS);
    } catch (IOException e) {
      if (!shuttingDown) {
        LOG.warn("DAP request '" + request.getCommand() + "' failed", e);
      }
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  List<Scope> requestScopes(int frameId) {
    ScopesRequest request = new ScopesRequest();
    ScopesArguments arguments = new ScopesArguments();
    arguments.setFrameId(frameId);
    request.setArguments(arguments);
    return sendRequest(request) instanceof ScopesResponse response && response.isSuccess()
           ? response.getBody().getScopes() : List.of();
  }

  List<Variable> requestVariables(int variablesReference) {
    VariablesRequest request = new VariablesRequest();
    VariablesArguments arguments = new VariablesArguments();
    arguments.setVariablesReference(variablesReference);
    request.setArguments(arguments);
    return sendRequest(request) instanceof VariablesResponse response && response.isSuccess()
           ? response.getBody().getVariables() : List.of();
  }

  /**
   * Sets the named child of a container reference to `value`. Returns the new
   * rendered value; throws with the server's message on failure.
   */
  String requestSetVariable(int containerReference, String name, String value) {
    SetVariableRequest request = new SetVariableRequest();
    SetVariableArguments arguments = new SetVariableArguments();
    arguments.setVariablesReference(containerReference);
    arguments.setName(name);
    arguments.setValue(value);
    request.setArguments(arguments);
    Response response = sendRequest(request);
    if (response instanceof SetVariableResponse ok && response.isSuccess()) {
      return ok.getBody() != null ? ok.getBody().getValue() : value;
    }
    throw new IllegalStateException(response != null && response.getMessage() != null
                                    ? response.getMessage() : "the debugger rejected the change");
  }

  // --- XDebugProcess wiring ---

  @Override
  protected @Nullable ProcessHandler doGetProcessHandler() {
    return processHandler;
  }

  @Override
  public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
    return new HaxeDebuggerEditorsProvider();
  }

  @Override
  public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
    return new XBreakpointHandler<?>[]{
      new XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>>(HaxeBreakpointType.class) {
        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
          breakpoints.register(breakpoint);
        }

        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {
          breakpoints.unregister(breakpoint);
        }
      }
    };
  }

  private static Thread daemon(Runnable work, String name) {
    Thread thread = new Thread(work, name);
    thread.setDaemon(true);
    return thread;
  }
}
