package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.plugins.haxe.runner.debugger.exceptions.HaxeExceptionBreakpointProperties;
import com.intellij.plugins.haxe.runner.debugger.exceptions.HaxeExceptionBreakpointType;
import com.intellij.plugins.haxe.runner.debugger.HaxeBreakpointType;
import com.intellij.plugins.haxe.runner.debugger.HaxeDebuggerEditorsProvider;
import com.intellij.plugins.haxe.runner.debugger.HaxeDebuggerSettings;
import com.intellij.plugins.haxe.runner.debugger.HaxeToStringRenderToggleAction;
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
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetExceptionBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetToStringRenderingRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetVariableArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetVariableRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepIntoFunctionArguments;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepIntoFunctionRequest;
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
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * XDebugger process for HXCPP (experimental): a DAP client over an {@link
 * HxcppDapBackend} (the in-process vshaxe adapter, or the debuggee's embedded
 * intellij-hxcpp-debug-server), bridging its events into the IDE. Mirrors the
 * HashLink debug process, but simpler in two ways: the peer is reached over a
 * loopback socket (no external process to manage), and the debuggee is a
 * plain child process (no OS-level debug attachment, so killing it needs no
 * special ceremony).
 *
 * The debuggee is spawned by the debug runner; its {@link ProcessHandler} is
 * the session's process handler, so console output, stdin and the exit code
 * flow through the normal run machinery, and the session ends when the
 * debuggee does.
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

  private final HxcppDapBackend backend;
  private final ProcessHandler processHandler;
  private final HxcppBreakpointManager breakpoints = new HxcppBreakpointManager(this);
  private final ExecutorService requestExecutor =
    Executors.newSingleThreadExecutor(r -> daemon(r, "HXCPP DAP requests"));

  private volatile DapClient client;
  private volatile int currentThreadId = 0;
  private volatile boolean shuttingDown = false;
  private volatile boolean launched = false;

  public HxcppDebugProcess(@NotNull XDebugSession session,
                           HxcppDapBackend backend, ProcessHandler debuggeeHandler) {
    super(session);
    this.backend = backend;
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
               + backend.startupHint());
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
      // blocks until the DAP peer is up (the adapter's loopback pair, or the
      // debuggee's embedded server connecting to the runner's listener)
      client = backend.connect();

      InitializeRequest initialize = new InitializeRequest();
      InitializeRequestArguments initializeArguments = new InitializeRequestArguments();
      initializeArguments.setAdapterID("intellij-haxe");
      initializeArguments.setClientID("intellij");
      initialize.setArguments(initializeArguments);
      client.sendRequest(initialize, REQUEST_TIMEOUT_MILLIS);
      client.pollEvent(REQUEST_TIMEOUT_MILLIS); // the initialized event

      if (backend.requiresLaunchRequest()) {
        // launch = "the debuggee's server connected"; it is held before main
        Response launchResponse = client.sendRequest(new LaunchRequest(), REQUEST_TIMEOUT_MILLIS);
        if (!launchResponse.isSuccess()) {
          fail("Cannot start the HXCPP debug session: " + launchResponse.getMessage());
          return;
        }
      }
      // either way the debuggee is attached now (a connected in-process server
      // holds the program before main until configurationDone)
      launched = true;

      breakpoints.flushAll();
      // Exception filters go IN-PHASE (before configurationDone), built by
      // reading the breakpoint manager: a breakpoint already enabled from a
      // previous IDE run arms here — the registerBreakpoint callbacks alone
      // fire on the EDT and would race startup (the HashLink lesson).
      if (backend.supportsExceptionFilters()) {
        sendRequest(exceptionFiltersRequest());
      }
      // object labels via toString: an off-default project setting; only a
      // non-default needs announcing (the server starts with it off)
      if (backend.supportsToStringRendering()
          && HaxeDebuggerSettings.getInstance(getSession().getProject()).isRenderObjectsWithToString()) {
        sendRequest(SetToStringRenderingRequest.of(true));
      }
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
      // prefer the runtime's own message (text) over the category (description)
      String text = stopped.getBody().getText();
      String description = stopped.getBody().getDescription();
      exceptionText = text != null ? text : (description != null ? description : "Exception thrown");
      // the gutter icon's tooltip is easy to miss - put the text where the
      // user is already looking
      print(exceptionText + "\n", true);
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
    HxcppSuspendContext context =
      new HxcppSuspendContext(this, threads, threadId, activeFrames, exceptionText);
    // resolve the top frame's source position HERE, on the pump thread:
    // positionReached's sessionPaused listeners read getCurrentPosition on the
    // EDT, where the resolver's index lookups are prohibited slow operations
    XExecutionStack activeStack = context.getActiveExecutionStack();
    XStackFrame topFrame = activeStack != null ? activeStack.getTopFrame() : null;
    if (topFrame != null) {
      topFrame.getSourcePosition();
    }
    getSession().positionReached(context);
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
  public @Nullable XSmartStepIntoHandler<?> getSmartStepIntoHandler() {
    return backend.supportsSmartStepInto() ? new HxcppSmartStepIntoHandler(this) : null;
  }

  // The Variables view's settings (gear) menu gets the toString-label toggle
  // when the backend can honor it (the vshaxe server cannot — it always
  // stringifies and offers no control, so no toggle is shown there).
  @Override
  public void registerAdditionalActions(@NotNull DefaultActionGroup leftToolbar,
                                        @NotNull DefaultActionGroup topToolbar,
                                        @NotNull DefaultActionGroup settings) {
    super.registerAdditionalActions(leftToolbar, topToolbar, settings);
    if (backend.supportsToStringRendering()) {
      settings.add(new HaxeToStringRenderToggleAction(getSession(), this::pushToStringRendering));
    }
  }

  // Live toggle: tell the server, then rebuild the views so the CURRENT
  // stop's variables re-render with the new labels (no restart needed).
  private void pushToStringRendering(boolean enabled) {
    onRequestThread(() -> {
      sendRequest(SetToStringRenderingRequest.of(enabled));
      getSession().rebuildViews();
    });
  }

  /**
   * Smart step into the chosen callee (custom intellij/stepIntoFunction
   * request). {@code occurrence} picks WHICH invocation on the line when the
   * same function is called more than once (1-based).
   */
  void stepIntoFunction(String className, String functionName, int occurrence) {
    StepIntoFunctionRequest request = new StepIntoFunctionRequest();
    StepIntoFunctionArguments arguments = new StepIntoFunctionArguments();
    arguments.setThreadId(currentThreadId);
    arguments.setClassName(className);
    arguments.setFunctionName(functionName);
    arguments.setOccurrence(occurrence);
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
      backend.close();
    } catch (IOException ignored) {
    }
    if (!processHandler.isProcessTerminated()) {
      processHandler.destroyProcess();
    }
  }

  // --- plumbing for breakpoints/frames/values ---

  /**
   * Runs work on the single-thread DAP request executor. Fire-and-forget:
   * work submitted while the session tears down is silently dropped — fine
   * for one-way requests, WRONG for work completing a promise, tree node or
   * callback (use the two-argument overload there).
   */
  void onRequestThread(Runnable work) {
    onRequestThread(work, () -> {
    });
  }

  /**
   * Runs work on the single-thread DAP request executor; when the session is
   * tearing down (executor already shut down), runs {@code onRejected} on the
   * calling thread instead. A caller holding a promise, tree node or callback
   * MUST complete it in {@code onRejected}, or the platform waits on it
   * forever (a hung smart-step popup, a permanent "Collecting data" node).
   */
  void onRequestThread(Runnable work, Runnable onRejected) {
    if (!requestExecutor.isShutdown()) {
      try {
        requestExecutor.execute(work);
        return;
      } catch (RejectedExecutionException ignored) {
        // shut down between the check and the submit
      }
    }
    onRejected.run();
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
    XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>> lineHandler =
      new XBreakpointHandler<>(HaxeBreakpointType.class) {
        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
          breakpoints.register(breakpoint);
        }

        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint, boolean temporary) {
          breakpoints.unregister(breakpoint);
        }
      };
    if (!backend.supportsExceptionFilters()) {
      return new XBreakpointHandler<?>[]{lineHandler};
    }
    return new XBreakpointHandler<?>[]{
      lineHandler,
      // the single shared Haxe exception category: any change (a toggle, a
      // Notifications checkbox, a per-class add) recomputes the filters
      new XBreakpointHandler<XBreakpoint<HaxeExceptionBreakpointProperties>>(HaxeExceptionBreakpointType.class) {
        @Override
        public void registerBreakpoint(@NotNull XBreakpoint<HaxeExceptionBreakpointProperties> breakpoint) {
          updateExceptionFilters();
        }

        @Override
        public void unregisterBreakpoint(@NotNull XBreakpoint<HaxeExceptionBreakpointProperties> breakpoint, boolean temporary) {
          updateExceptionFilters();
        }
      }
    };
  }

  // Recomputed whenever an exception breakpoint toggles (live path — posted to
  // the request thread). See exceptionFiltersRequest for how the set is built.
  private void updateExceptionFilters() {
    onRequestThread(() -> sendRequest(exceptionFiltersRequest()));
  }

  // This server's exception-filter vocabulary (must match the
  // intellij-hxcpp-debug-server's Dispatcher / the vshaxe adapter):
  /** Stop on every throw, caught or not (the haxe.Exception.new hook). */
  private static final String FILTER_ANY_THROW = "thrown";
  /** Stop when no catch will handle the throw. */
  private static final String FILTER_UNCAUGHT = "uncaught";
  /** Stop on hxcpp critical errors: null access, out-of-bounds, ... */
  private static final String FILTER_CRITICAL_ERROR = "critical";

  // The Notifications checkboxes mapped onto THIS server's filter vocabulary;
  // see HaxeExceptionBreakpointType.buildFiltersRequest for how the set is built.
  private SetExceptionBreakpointsRequest exceptionFiltersRequest() {
    return HaxeExceptionBreakpointType.buildFiltersRequest(
      getSession().getProject(), FILTER_ANY_THROW, FILTER_UNCAUGHT, FILTER_CRITICAL_ERROR);
  }

  private static Thread daemon(Runnable work, String name) {
    Thread thread = new Thread(work, name);
    thread.setDaemon(true);
    return thread;
  }
}
