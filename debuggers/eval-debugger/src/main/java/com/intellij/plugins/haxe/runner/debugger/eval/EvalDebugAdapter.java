package com.intellij.plugins.haxe.runner.debugger.eval;

import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Breakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Capabilities;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.DapThread;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Event;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.ProtocolMessage;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Scope;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Source;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.SourceBreakpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StackFrame;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Variable;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.InitializedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.StoppedEventBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.TerminatedEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.ThreadEvent;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.events.ThreadEventBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfigurationDoneRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ContinueRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.DisconnectRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.EvaluateRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.LaunchRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.PauseRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetExceptionBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepOutRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ThreadsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.VariablesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ConfigurationDoneResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ContinueResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.DisconnectResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ErrorMessage;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ErrorResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ErrorResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.EvaluateResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.EvaluateResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.InitializeResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.LaunchResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.NextResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.PauseResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ScopesResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetBreakpointsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetBreakpointsResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetExceptionBreakpointsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StackTraceResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StepInResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.StepOutResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ThreadsResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.ThreadsResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.VariablesResponseBody;
import com.intellij.plugins.haxe.runner.debugger.dap.transport.DapConnection;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * The in-process DAP adapter for the EVAL debugger: presents a DAP server to
 * the IDE-side {@code DapClient} and translates every request into the haxe
 * compiler's eval-debugger JSON-RPC protocol (and its notifications back into
 * DAP events). Reference: vshaxe/eval-debugger's Main.hx; the wire behaviour
 * this relies on is live-verified in {@code EvalLiveTest} / docs.
 *
 * Lifecycle: construct (binds the VM listener socket), let the caller spawn
 * {@code haxe <args> -D eval-debugger=127.0.0.1:<port> --interp} (or any
 * compilation whose MACROS should be debugged), then {@link #start} with the
 * DAP connection. The eval VM connects to our listener and WAITS before
 * running main; configurationDone releases it with the protocol's continue.
 *
 * Simpler than the hxcpp sibling in two load-bearing ways: the VM assigns a
 * single id space for scopes and variables (so variablesReference IS the
 * VM id — no path registry), and stops always carry the thread id.
 */
public class EvalDebugAdapter implements Closeable {

  private final ServerSocket vmListener;
  private final long vmConnectTimeoutMillis;
  private final CompletableFuture<EvalProtocol> protocolFuture = new CompletableFuture<>();
  private final AtomicInteger nextSeq = new AtomicInteger(1);

  private DapConnection dap;
  private EvalConnection vmConnection;
  private Thread acceptThread;
  private Thread requestThread;
  private volatile boolean closed = false;
  /** Thread the VM last reported stopped; null while running. */
  private volatile Integer stoppedThreadId;

  public EvalDebugAdapter(long vmConnectTimeoutMillis) throws IOException {
    this.vmConnectTimeoutMillis = vmConnectTimeoutMillis;
    vmListener = new ServerSocket();
    vmListener.setReuseAddress(false);
    vmListener.bind(new InetSocketAddress("127.0.0.1", 0));
  }

  /** The port for the debuggee's {@code -D eval-debugger=127.0.0.1:<port>}. */
  public int getVmPort() {
    return vmListener.getLocalPort();
  }

  /** Starts the adapter threads against an established DAP connection. */
  public void start(DapConnection dapConnection) {
    this.dap = dapConnection;
    acceptThread = daemon("eval-vm-accept", this::acceptVm);
    requestThread = daemon("eval-dap-requests", this::requestLoop);
  }

  private static Thread daemon(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  // ---------------------------------------------------------------------- VM

  private void acceptVm() {
    try {
      Socket socket = vmListener.accept();
      vmConnection = new EvalConnection(socket.getInputStream(), socket.getOutputStream());
      // listener registered BEFORE start so no early notification is dropped
      vmConnection.setEventListener(this::handleVmEvent);
      vmConnection.setOnDisconnected(() -> {
        // the eval VM's socket closing means the program (or the compilation
        // being macro-debugged) finished — that IS session termination
        if (!closed) {
          sendEventQuietly(new TerminatedEvent());
        }
      });
      vmConnection.start();
      protocolFuture.complete(new EvalProtocol(vmConnection));
    } catch (IOException e) {
      if (!closed) {
        protocolFuture.completeExceptionally(e);
      } else {
        protocolFuture.cancel(false);
      }
    }
  }

  /** The connected protocol, waiting for the VM when necessary. */
  private EvalProtocol vm(long timeoutMillis) throws IOException {
    try {
      return protocolFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IOException("The haxe eval VM did not connect within " + timeoutMillis + " ms. "
                            + "Was haxe launched with -D eval-debugger=127.0.0.1:" + getVmPort() + "?");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for the eval VM to connect");
    } catch (ExecutionException | CancellationException e) {
      throw new IOException("Eval VM connection failed: " + e.getCause(), e.getCause());
    }
  }

  private EvalProtocol vm() throws IOException {
    return vm(vmConnectTimeoutMillis);
  }

  // ------------------------------------------------------------ DAP requests

  private void requestLoop() {
    try {
      while (true) {
        ProtocolMessage message = dap.receive();
        if (message == null) {
          return;
        }
        if (message instanceof Request request) {
          try {
            dispatch(request);
          } catch (Exception e) {
            // answer THIS request with the failure; the loop itself must survive,
            // or every later request times out and the client's views go blank
            sendErrorResponse(request, e.getMessage() != null ? e.getMessage() : e.toString());
          }
        }
      }
    } catch (IOException e) {
      if (!closed) {
        System.err.println("EvalDebugAdapter DAP request loop died: " + e);
      }
    }
  }

  private void dispatch(Request request) throws IOException {
    switch (request) {
      case InitializeRequest r -> handleInitialize(r);
      case LaunchRequest r -> handleLaunch(r);
      case SetBreakpointsRequest r -> handleSetBreakpoints(r);
      case SetExceptionBreakpointsRequest r -> handleSetExceptionBreakpoints(r);
      case ConfigurationDoneRequest r -> handleConfigurationDone(r);
      case ThreadsRequest r -> handleThreads(r);
      case StackTraceRequest r -> handleStackTrace(r);
      case ScopesRequest r -> handleScopes(r);
      case VariablesRequest r -> handleVariables(r);
      case ContinueRequest r -> handleContinue(r);
      case NextRequest r -> handleStep(r);
      case StepInRequest r -> handleStep(r);
      case StepOutRequest r -> handleStep(r);
      case PauseRequest r -> handlePause(r);
      case EvaluateRequest r -> handleEvaluate(r);
      case DisconnectRequest r -> handleDisconnect(r);
      default -> sendErrorResponse(request, "Unsupported request '" + request.getCommand() + "'");
    }
  }

  private void handleInitialize(InitializeRequest request) throws IOException {
    Capabilities capabilities = new Capabilities();
    capabilities.setSupportsConfigurationDoneRequest(true);
    capabilities.setSupportsVariableType(true);
    capabilities.setSupportsEvaluateForHovers(true);
    InitializeResponse response = new InitializeResponse();
    response.setBody(capabilities);
    sendResponse(request, response);
    sendEvent(new InitializedEvent());
  }

  private void handleLaunch(LaunchRequest request) throws IOException {
    // the haxe process is spawned by the caller (IDE runner / test); launch
    // just means "the VM connected and is held waiting before main"
    vm();
    sendResponse(request, new LaunchResponse());
  }

  private void handleSetBreakpoints(SetBreakpointsRequest request) throws IOException {
    String file = request.getArguments().getSource().getPath();
    List<SourceBreakpoint> requested = request.getArguments().getBreakpoints() != null
                                       ? request.getArguments().getBreakpoints() : List.of();
    int[] lines = new int[requested.size()];
    for (int i = 0; i < requested.size(); i++) {
      lines[i] = requested.get(i).getLine();
    }
    List<EvalProtocol.EvalBreakpoint> registered = vm().setBreakpoints(file, lines);

    List<Breakpoint> verified = new ArrayList<>();
    for (int i = 0; i < requested.size(); i++) {
      Breakpoint breakpoint = new Breakpoint();
      breakpoint.setVerified(true);
      breakpoint.setLine(requested.get(i).getLine());
      breakpoint.setSource(request.getArguments().getSource());
      if (i < registered.size()) {
        breakpoint.setId(registered.get(i).id());
      }
      verified.add(breakpoint);
    }
    SetBreakpointsResponseBody body = new SetBreakpointsResponseBody();
    body.setBreakpoints(verified);
    SetBreakpointsResponse response = new SetBreakpointsResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleSetExceptionBreakpoints(SetExceptionBreakpointsRequest request) throws IOException {
    List<String> filters = request.getArguments() != null && request.getArguments().getFilters() != null
                           ? request.getArguments().getFilters() : List.of();
    // the VM understands "all"/"uncaught" exception options; anything we
    // cannot express is dropped rather than failing the whole configure
    vm().setExceptionOptions(filters);
    sendResponse(request, new SetExceptionBreakpointsResponse());
  }

  private void handleConfigurationDone(ConfigurationDoneRequest request) throws IOException {
    // release the VM, which waits before main (script) / the macro (build)
    resumed();
    resumeToleratingExit();
    sendResponse(request, new ConfigurationDoneResponse());
  }

  /**
   * Resumes the VM, treating a connection close during the request as
   * SUCCESS: the VM acks continue from a helper thread while the resumed
   * program runs, and when the program finishes the process can exit before
   * that ack is flushed — the resume happened, the terminated event (from
   * the disconnect callback) ends the session. Live-observed race.
   */
  private void resumeToleratingExit() throws IOException {
    try {
      vm().resume();
    } catch (EvalConnectionClosedException ignored) {
      // program ran to completion during the resume
    }
  }

  private void handleThreads(ThreadsRequest request) throws IOException {
    List<DapThread> dapThreads = new ArrayList<>();
    for (EvalProtocol.EvalThread thread : vm().getThreads()) {
      DapThread dapThread = new DapThread();
      dapThread.setId(thread.id());
      dapThread.setName(thread.name());
      dapThreads.add(dapThread);
    }
    ThreadsResponseBody body = new ThreadsResponseBody();
    body.setThreads(dapThreads);
    ThreadsResponse response = new ThreadsResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleStackTrace(StackTraceRequest request) throws IOException {
    int threadId = request.getArguments().getThreadId();
    List<StackFrame> stackFrames = new ArrayList<>();
    for (EvalProtocol.EvalStackFrame frame : vm().stackTrace(threadId)) {
      if (frame.artificial()) {
        continue; // interpreter-internal frames are noise to the user
      }
      StackFrame stackFrame = new StackFrame();
      stackFrame.setId(frame.id());
      stackFrame.setName(frame.name());
      stackFrame.setLine(frame.line());
      stackFrame.setColumn(frame.column());
      stackFrame.setSource(toSource(frame.source()));
      stackFrames.add(stackFrame);
    }
    StackTraceResponseBody body = new StackTraceResponseBody();
    body.setStackFrames(stackFrames);
    body.setTotalFrames(stackFrames.size());
    StackTraceResponse response = new StackTraceResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleScopes(ScopesRequest request) throws IOException {
    List<Scope> scopes = new ArrayList<>();
    for (EvalProtocol.EvalScope scopeInfo : vm().getScopes(request.getArguments().getFrameId())) {
      Scope scope = new Scope();
      scope.setName(scopeInfo.name());
      scope.setVariablesReference(scopeInfo.id());
      scope.setExpensive(false);
      scopes.add(scope);
    }
    ScopesResponseBody body = new ScopesResponseBody();
    body.setScopes(scopes);
    ScopesResponse response = new ScopesResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleVariables(VariablesRequest request) throws IOException {
    List<Variable> variables = new ArrayList<>();
    for (EvalProtocol.EvalVar var : vm().getVariables(request.getArguments().getVariablesReference())) {
      variables.add(toVariable(var));
    }
    VariablesResponseBody body = new VariablesResponseBody();
    body.setVariables(variables);
    VariablesResponse response = new VariablesResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleContinue(ContinueRequest request) throws IOException {
    resumed();
    resumeToleratingExit();
    sendResponse(request, new ContinueResponse());
  }

  private void handleStep(Request request) throws IOException {
    Integer thread = stoppedThreadId;
    if (thread == null) {
      sendErrorResponse(request, "Cannot step: the debuggee is not stopped");
      return;
    }
    // the VM's step request is SYNCHRONOUS: its response arrives when the
    // step has LANDED, and no notification follows — the adapter must
    // synthesize the DAP stopped("step") itself (vshaxe's Main.hx does the
    // same). The thread stays logically stopped through the whole step.
    // A step over the program's LAST line races process exit like continue
    // does: connection close during the step means it ran off the end.
    boolean programEnded = false;
    Response response;
    try {
      switch (request) {
        case NextRequest r -> vm().next();
        case StepInRequest r -> vm().stepIn();
        default -> vm().stepOut();
      }
    } catch (EvalConnectionClosedException ignored) {
      programEnded = true;
    }
    response = switch (request) {
      case NextRequest r -> new NextResponse();
      case StepInRequest r -> new StepInResponse();
      default -> new StepOutResponse();
    };
    sendResponse(request, response);
    if (!programEnded) {
      sendStopped("step", thread, null);
    }
  }

  private void handlePause(PauseRequest request) throws IOException {
    vm().pause();
    sendResponse(request, new PauseResponse());
  }

  private void handleEvaluate(EvaluateRequest request) throws IOException {
    Integer frameId = request.getArguments().getFrameId();
    if (frameId == null) {
      sendErrorResponse(request, "evaluate requires a frameId (no frame context without a stopped stack)");
      return;
    }
    EvalProtocol.EvalVar result = vm().evaluate(request.getArguments().getExpression(), frameId);
    EvaluateResponseBody body = new EvaluateResponseBody();
    body.setResult(result.value());
    body.setType(result.type());
    body.setVariablesReference(result.numChildren() > 0 ? result.id() : 0);
    EvaluateResponse response = new EvaluateResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleDisconnect(DisconnectRequest request) throws IOException {
    closed = true;
    if (vmConnection != null) {
      vmConnection.close();
    }
    vmListener.close();
    sendResponse(request, new DisconnectResponse());
  }

  // ------------------------------------------------------ eval -> DAP events

  private void handleVmEvent(String method, JsonNode params) {
    try {
      switch (method) {
        case EvalProtocol.EVENT_BREAKPOINT_STOP ->
          sendStopped("breakpoint", params.path("threadId").asInt(0), null);
        case EvalProtocol.EVENT_EXCEPTION_STOP ->
          sendStopped("exception", params.path("threadId").asInt(0), params.path("text").asString(""));
        case EvalProtocol.EVENT_THREAD_EVENT ->
          sendThreadEvent(params.path("reason").asString(""), params.path("threadId").asInt(0));
        default -> System.err.println("EvalDebugAdapter: unknown notification '" + method + "'");
      }
    } catch (IOException e) {
      System.err.println("EvalDebugAdapter failed to forward '" + method + "': " + e);
    }
  }

  private void sendStopped(String reason, int threadId, String description) throws IOException {
    stoppedThreadId = threadId;
    StoppedEventBody body = new StoppedEventBody();
    body.setReason(reason);
    body.setThreadId(threadId);
    body.setAllThreadsStopped(true);
    body.setDescription(description);
    StoppedEvent event = new StoppedEvent();
    event.setBody(body);
    sendEvent(event);
  }

  private void sendThreadEvent(String reason, int threadId) throws IOException {
    ThreadEventBody body = new ThreadEventBody();
    body.setReason(reason);
    body.setThreadId(threadId);
    ThreadEvent event = new ThreadEvent();
    event.setBody(body);
    sendEvent(event);
  }

  /** The debuggee is about to run again: references die with the stop. */
  private void resumed() {
    stoppedThreadId = null;
  }

  // ------------------------------------------------------------------ helpers

  private static Source toSource(String sourcePath) {
    if (sourcePath == null || sourcePath.isEmpty() || sourcePath.equals("?")) {
      return null;
    }
    try {
      Source source = new Source();
      source.setPath(sourcePath);
      Path fileName = Path.of(sourcePath).getFileName();
      source.setName(fileName != null ? fileName.toString() : sourcePath);
      return source;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static Variable toVariable(EvalProtocol.EvalVar var) {
    Variable variable = new Variable();
    variable.setName(var.name());
    variable.setValue(var.value());
    variable.setType(var.type());
    variable.setVariablesReference(var.numChildren() > 0 ? var.id() : 0);
    return variable;
  }

  private void sendResponse(Request request, Response response) throws IOException {
    response.setSeq(nextSeq.getAndIncrement());
    response.setRequest_seq(request.getSeq());
    response.setCommand(request.getCommand());
    response.setSuccess(true);
    dap.send(response);
  }

  private void sendErrorResponse(Request request, String message) throws IOException {
    ErrorMessage errorMessage = new ErrorMessage();
    errorMessage.setId(0);
    errorMessage.setFormat(message);
    errorMessage.setShowUser(true);
    ErrorResponseBody body = new ErrorResponseBody();
    body.setError(errorMessage);
    ErrorResponse response = new ErrorResponse();
    response.setBody(body);
    response.setSeq(nextSeq.getAndIncrement());
    response.setRequest_seq(request.getSeq());
    response.setCommand(request.getCommand());
    response.setSuccess(false);
    response.setMessage(message);
    dap.send(response);
  }

  private void sendEvent(Event event) throws IOException {
    event.setSeq(nextSeq.getAndIncrement());
    dap.send(event);
  }

  private void sendEventQuietly(Event event) {
    try {
      sendEvent(event);
    } catch (IOException ignored) {
      // DAP side already gone; nothing left to tell
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    protocolFuture.cancel(false);
    if (vmConnection != null) {
      vmConnection.close();
    }
    vmListener.close();
  }
}
