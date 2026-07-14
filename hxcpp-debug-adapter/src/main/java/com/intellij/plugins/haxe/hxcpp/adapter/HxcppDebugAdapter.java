package com.intellij.plugins.haxe.hxcpp.adapter;

import com.intellij.plugins.haxe.hxcpp.jsonrpc.JsonRpcClient;
import com.intellij.plugins.haxe.hxcpp.jsonrpc.JsonRpcConnection;
import com.intellij.plugins.haxe.hxcpp.protocol.HxcppProtocol;
import com.intellij.plugins.haxe.hxcpp.protocol.HxcppScopeInfo;
import com.intellij.plugins.haxe.hxcpp.protocol.HxcppStackFrameInfo;
import com.intellij.plugins.haxe.hxcpp.protocol.HxcppThreadInfo;
import com.intellij.plugins.haxe.hxcpp.protocol.HxcppVarInfo;
import com.intellij.plugins.haxe.hxcpp.jsonrpc.JsonRpcJson;
import com.intellij.plugins.haxe.hxcpp.jsonrpc.JsonRpcNotification;
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
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.NextRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.PauseRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ScopesRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetExceptionBreakpointsRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.SetVariableRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StackTraceRequest;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.StepInRequest;
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
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetVariableResponse;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.responses.SetVariableResponseBody;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * The in-process DAP adapter for the HXCPP debugger: presents a DAP server to
 * the IDE-side {@code DapClient} and translates every request into the
 * hxcpp-debug-server jsonrpc protocol (and its notifications back into DAP
 * events). The reference for each translation is Adapter.hx in
 * vshaxe/hxcpp-debugger; the protocol mismatches this layer absorbs are
 * documented in docs/implementation-plan.md.
 *
 * Lifecycle: construct (binds the debuggee listener socket), let the caller
 * spawn the debuggee (compiled with -lib hxcpp-debug-server -debug and defines
 * matching {@link #getDebuggeePort()}), then {@link #start} with the DAP
 * connection. The debuggee's Server connects to our listener, holds the app
 * stopped, and is released by the jsonrpc continue sent on configurationDone.
 *
 * Threading: one thread runs the DAP request loop (requests are handled
 * strictly one at a time; a handler failure answers that request with an
 * error and must never kill the loop), one accepts the debuggee connection,
 * and one pumps jsonrpc notifications into DAP events. DAP-side writes are
 * serialized by {@link DapConnection#send}.
 */
public class HxcppDebugAdapter implements Closeable {
  private static final long RPC_TIMEOUT_MILLIS = 15_000;

  private final ServerSocket debuggeeListener;
  private final long debuggeeConnectTimeoutMillis;
  private final CompletableFuture<JsonRpcClient> clientFuture = new CompletableFuture<>();
  private final AtomicInteger nextSeq = new AtomicInteger(1);
  private final VariablePathRegistry variablePaths = new VariablePathRegistry();

  private DapConnection dap;
  private Thread acceptThread;
  private Thread requestThread;
  private Thread pumpThread;
  private volatile boolean closed = false;
  /** Thread the server last reported stopped; null while running. */
  private volatile Integer stoppedThreadId;

  public HxcppDebugAdapter(String host, int port, long debuggeeConnectTimeoutMillis) throws IOException {
    this.debuggeeConnectTimeoutMillis = debuggeeConnectTimeoutMillis;
    debuggeeListener = new ServerSocket();
    // never share the port: a leftover instance of the debugged program (e.g.
    // from a plain Run - its embedded server binds the port itself when no
    // debugger answers) must surface as a clear bind failure here, not as a
    // silent double-bind that poisons every later connection
    debuggeeListener.setReuseAddress(false);
    debuggeeListener.bind(new InetSocketAddress(host, port));
  }

  /** The port the debuggee's Server must connect to (HXCPP_DEBUG_PORT). */
  public int getDebuggeePort() {
    return debuggeeListener.getLocalPort();
  }

  /** Starts the adapter threads against an established DAP connection. */
  public void start(DapConnection dapConnection) {
    this.dap = dapConnection;
    acceptThread = daemon("hxcpp-debuggee-accept", this::acceptDebuggee);
    requestThread = daemon("hxcpp-dap-requests", this::requestLoop);
    pumpThread = daemon("hxcpp-event-pump", this::pumpLoop);
  }

  private static Thread daemon(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  // ---------------------------------------------------------------- debuggee

  private void acceptDebuggee() {
    try {
      Socket socket = debuggeeListener.accept();
      clientFuture.complete(new JsonRpcClient(new JsonRpcConnection(socket)));
    } catch (IOException e) {
      if (!closed) {
        clientFuture.completeExceptionally(e);
      } else {
        clientFuture.cancel(false);
      }
    }
  }

  /** The connected jsonrpc client, waiting for the debuggee when necessary. */
  private JsonRpcClient client(long timeoutMillis) throws IOException {
    try {
      return clientFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IOException("Debuggee did not connect to the debugger within " + timeoutMillis + " ms. "
                            + "Was it compiled with -debug and -lib hxcpp-debug-server, "
                            + "with HXCPP_DEBUG_PORT matching " + getDebuggeePort() + "? "
                            + "A previous instance of the program still running (e.g. from a plain Run) "
                            + "also blocks the debug port - stop any leftover instances and retry.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for the debuggee to connect");
    } catch (ExecutionException | java.util.concurrent.CancellationException e) {
      throw new IOException("Debuggee connection failed: " + e.getCause(), e.getCause());
    }
  }

  private JsonNode call(String method, Object params) throws IOException, InterruptedException {
    return client(RPC_TIMEOUT_MILLIS).call(method, params, RPC_TIMEOUT_MILLIS);
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
        System.err.println("HxcppDebugAdapter DAP request loop died: " + e);
        e.printStackTrace();
      }
    }
  }

  private void dispatch(Request request) throws IOException, InterruptedException {
    if (request instanceof InitializeRequest r) {
      handleInitialize(r);
    } else if (request instanceof LaunchRequest r) {
      handleLaunch(r);
    } else if (request instanceof SetBreakpointsRequest r) {
      handleSetBreakpoints(r);
    } else if (request instanceof SetExceptionBreakpointsRequest r) {
      handleSetExceptionBreakpoints(r);
    } else if (request instanceof ConfigurationDoneRequest r) {
      handleConfigurationDone(r);
    } else if (request instanceof ThreadsRequest r) {
      handleThreads(r);
    } else if (request instanceof StackTraceRequest r) {
      handleStackTrace(r);
    } else if (request instanceof ScopesRequest r) {
      handleScopes(r);
    } else if (request instanceof VariablesRequest r) {
      handleVariables(r);
    } else if (request instanceof ContinueRequest r) {
      handleContinue(r);
    } else if (request instanceof NextRequest r) {
      handleStep(r, HxcppProtocol.NEXT, r.getArguments() != null ? r.getArguments().getThreadId() : null);
    } else if (request instanceof StepInRequest r) {
      handleStep(r, HxcppProtocol.STEP_IN, r.getArguments() != null ? r.getArguments().getThreadId() : null);
    } else if (request instanceof StepOutRequest r) {
      handleStep(r, HxcppProtocol.STEP_OUT, r.getArguments() != null ? r.getArguments().getThreadId() : null);
    } else if (request instanceof PauseRequest r) {
      handlePause(r);
    } else if (request instanceof EvaluateRequest r) {
      handleEvaluate(r);
    } else if (request instanceof SetVariableRequest r) {
      handleSetVariable(r);
    } else if (request instanceof DisconnectRequest r) {
      handleDisconnect(r);
    } else {
      sendErrorResponse(request, "Unsupported request '" + request.getCommand() + "'");
    }
  }

  private void handleInitialize(InitializeRequest request) throws IOException {
    Capabilities capabilities = new Capabilities();
    capabilities.setSupportsConfigurationDoneRequest(true);
    capabilities.setSupportsVariableType(true);
    capabilities.setSupportsEvaluateForHovers(true);
    capabilities.setSupportsSetVariable(true);
    capabilities.setSupportsConditionalBreakpoints(true);
    InitializeResponse response = new InitializeResponse();
    response.setBody(capabilities);
    sendResponse(request, response);
    sendEvent(new InitializedEvent());
  }

  private void handleLaunch(LaunchRequest request) throws IOException {
    // the debuggee is spawned by the caller (IDE runner / test); launch just
    // means "it connected and is held stopped by its initial breakNow"
    client(debuggeeConnectTimeoutMillis);
    sendResponse(request, new LaunchResponse());
  }

  private void handleSetBreakpoints(SetBreakpointsRequest request) throws IOException, InterruptedException {
    String file = request.getArguments().getSource().getPath();
    List<Map<String, Object>> breakpoints = new ArrayList<>();
    List<SourceBreakpoint> requested = request.getArguments().getBreakpoints() != null
                                       ? request.getArguments().getBreakpoints() : List.of();
    for (SourceBreakpoint sourceBreakpoint : requested) {
      Map<String, Object> breakpoint = new HashMap<>();
      breakpoint.put("line", sourceBreakpoint.getLine());
      if (sourceBreakpoint.getColumn() != null) {
        breakpoint.put("column", sourceBreakpoint.getColumn());
      }
      if (sourceBreakpoint.getCondition() != null) {
        breakpoint.put("condition", sourceBreakpoint.getCondition());
      }
      breakpoints.add(breakpoint);
    }

    JsonNode result = call(HxcppProtocol.SET_BREAKPOINTS, Map.of("file", file, "breakpoints", breakpoints));

    List<Breakpoint> verified = new ArrayList<>();
    for (int i = 0; i < requested.size(); i++) {
      Breakpoint breakpoint = new Breakpoint();
      breakpoint.setVerified(true);
      breakpoint.setLine(requested.get(i).getLine());
      breakpoint.setSource(request.getArguments().getSource());
      if (result != null && result.has(i)) {
        breakpoint.setId(result.get(i).path("id").asInt());
      }
      verified.add(breakpoint);
    }
    SetBreakpointsResponseBody body = new SetBreakpointsResponseBody();
    body.setBreakpoints(verified);
    SetBreakpointsResponse response = new SetBreakpointsResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleSetExceptionBreakpoints(SetExceptionBreakpointsRequest request)
    throws IOException, InterruptedException {
    // Protocol.hx: setExceptionOptions takes a bare array of strings
    List<String> filters = request.getArguments() != null && request.getArguments().getFilters() != null
                           ? request.getArguments().getFilters() : List.of();
    call(HxcppProtocol.SET_EXCEPTION_OPTIONS, filters);
    sendResponse(request, new SetExceptionBreakpointsResponse());
  }

  private void handleConfigurationDone(ConfigurationDoneRequest request) throws IOException, InterruptedException {
    // release the debuggee held by the Server's initial breakNow
    resumed();
    call(HxcppProtocol.CONTINUE, Map.of("threadId", 0));
    sendResponse(request, new ConfigurationDoneResponse());
  }

  private void handleThreads(ThreadsRequest request) throws IOException, InterruptedException {
    HxcppThreadInfo[] threads = decode(call(HxcppProtocol.THREADS, null), HxcppThreadInfo[].class);
    List<DapThread> dapThreads = new ArrayList<>();
    for (HxcppThreadInfo thread : threads) {
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

  private void handleStackTrace(StackTraceRequest request) throws IOException, InterruptedException {
    int threadId = request.getArguments().getThreadId();
    HxcppStackFrameInfo[] frames =
      decode(call(HxcppProtocol.STACK_TRACE, Map.of("threadId", threadId)), HxcppStackFrameInfo[].class);
    List<StackFrame> stackFrames = new ArrayList<>();
    for (HxcppStackFrameInfo frame : frames) {
      if (Boolean.TRUE.equals(frame.artificial())) {
        continue; // debugger-internal frames are noise to the user
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

  private void handleScopes(ScopesRequest request) throws IOException, InterruptedException {
    int frameId = request.getArguments().getFrameId();
    HxcppScopeInfo[] scopeInfos =
      decode(call(HxcppProtocol.GET_SCOPES, Map.of("frameId", frameId)), HxcppScopeInfo[].class);
    List<Scope> scopes = new ArrayList<>();
    for (HxcppScopeInfo scopeInfo : scopeInfos) {
      variablePaths.registerScope(scopeInfo.id());
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

  private void handleVariables(VariablesRequest request) throws IOException, InterruptedException {
    int reference = request.getArguments().getVariablesReference();
    Map<String, Object> params = new HashMap<>();
    params.put("variablesReference", reference);
    if (request.getArguments().getStart() != null) {
      params.put("start", request.getArguments().getStart());
    }
    if (request.getArguments().getCount() != null) {
      params.put("count", request.getArguments().getCount());
    }
    HxcppVarInfo[] varInfos = decode(call(HxcppProtocol.GET_VARIABLES, params), HxcppVarInfo[].class);
    List<Variable> variables = new ArrayList<>();
    for (HxcppVarInfo varInfo : varInfos) {
      variablePaths.registerChild(reference, varInfo.name(), varInfo.variablesReference());
      variables.add(toVariable(varInfo));
    }
    VariablesResponseBody body = new VariablesResponseBody();
    body.setVariables(variables);
    VariablesResponse response = new VariablesResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleContinue(ContinueRequest request) throws IOException, InterruptedException {
    int threadId = request.getArguments() != null ? request.getArguments().getThreadId() : 0;
    resumed();
    call(HxcppProtocol.CONTINUE, Map.of("threadId", threadId));
    sendResponse(request, new ContinueResponse());
  }

  /**
   * The server's step requests take no threadId — they act on its current
   * (stopped) thread. Stepping any other thread cannot be honored.
   */
  private void handleStep(Request request, String method, Integer threadId)
    throws IOException, InterruptedException {
    Integer stopped = stoppedThreadId;
    if (stopped == null) {
      sendErrorResponse(request, "Cannot step: the debuggee is not stopped");
      return;
    }
    if (threadId != null && threadId != stopped.intValue()) {
      sendErrorResponse(request, "Can only step the stopped thread (" + stopped + "), not thread " + threadId);
      return;
    }
    resumed();
    call(method, null);
    Response response = switch (method) {
      case HxcppProtocol.NEXT -> new NextResponse();
      case HxcppProtocol.STEP_IN -> new StepInResponse();
      default -> new StepOutResponse();
    };
    sendResponse(request, response);
  }

  private void handlePause(PauseRequest request) throws IOException, InterruptedException {
    call(HxcppProtocol.PAUSE, null);
    sendResponse(request, new PauseResponse());
  }

  private void handleEvaluate(EvaluateRequest request) throws IOException, InterruptedException {
    Integer frameId = request.getArguments().getFrameId();
    if (frameId == null) {
      sendErrorResponse(request, "evaluate requires a frameId (no frame context without a stopped stack)");
      return;
    }
    String expression = request.getArguments().getExpression();
    HxcppVarInfo varInfo =
      decode(call(HxcppProtocol.EVALUATE, Map.of("expr", expression, "frameId", frameId)), HxcppVarInfo.class);
    variablePaths.registerExpression(varInfo.variablesReference(), expression);
    EvaluateResponseBody body = new EvaluateResponseBody();
    body.setResult(varInfo.value());
    body.setType(varInfo.type());
    body.setVariablesReference(varInfo.variablesReference());
    EvaluateResponse response = new EvaluateResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleSetVariable(SetVariableRequest request) throws IOException, InterruptedException {
    int reference = request.getArguments().getVariablesReference();
    String name = request.getArguments().getName();
    String expression = variablePaths.childExpression(reference, name);
    if (expression == null) {
      sendErrorResponse(request, "Stale variablesReference " + reference
                                 + " - references are only valid while stopped");
      return;
    }
    HxcppVarInfo varInfo = decode(
      call(HxcppProtocol.SET_VARIABLE, Map.of("expr", expression, "value", request.getArguments().getValue())),
      HxcppVarInfo.class);
    variablePaths.registerExpression(varInfo.variablesReference(), expression);
    SetVariableResponseBody body = new SetVariableResponseBody();
    body.setValue(varInfo.value());
    body.setType(varInfo.type());
    body.setVariablesReference(varInfo.variablesReference());
    SetVariableResponse response = new SetVariableResponse();
    response.setBody(body);
    sendResponse(request, response);
  }

  private void handleDisconnect(DisconnectRequest request) throws IOException {
    closed = true;
    if (clientFuture.isDone() && !clientFuture.isCompletedExceptionally() && !clientFuture.isCancelled()) {
      try {
        clientFuture.get().close();
      } catch (Exception ignored) {
        // already tearing down; the response below is what matters
      }
    }
    debuggeeListener.close();
    sendResponse(request, new DisconnectResponse());
  }

  // ----------------------------------------------------- jsonrpc -> DAP events

  private void pumpLoop() {
    JsonRpcClient client;
    try {
      client = clientFuture.get();
    } catch (Exception e) {
      return; // never connected; launch already reported the failure
    }
    try {
      while (!closed) {
        JsonRpcNotification notification = client.pollNotification(250);
        if (notification != null) {
          try {
            handleNotification(notification);
          } catch (Exception e) {
            System.err.println("HxcppDebugAdapter failed to translate notification '"
                               + notification.method() + "': " + e);
          }
        }
        else if (client.isConnectionFinished()) {
          sendEventQuietly(new TerminatedEvent());
          return;
        }
      }
    } catch (InterruptedException ignored) {
      // adapter shutdown
    }
  }

  private void handleNotification(JsonRpcNotification notification) throws IOException {
    switch (notification.method()) {
      case HxcppProtocol.NOTIFY_BREAKPOINT_STOP ->
        sendStopped("breakpoint", notification.params().path("threadId").asInt(), null);
      case HxcppProtocol.NOTIFY_PAUSE_STOP ->
        sendStopped("pause", notification.params().path("threadId").asInt(), null);
      case HxcppProtocol.NOTIFY_EXCEPTION_STOP ->
        // the protocol carries no threadId for exceptions; 0 mirrors the vshaxe adapter
        sendStopped("exception", 0, notification.params().path("text").asString(""));
      case HxcppProtocol.NOTIFY_THREAD_START ->
        sendThreadEvent(ThreadEvent.REASON_STARTED, notification.params().path("threadId").asInt());
      case HxcppProtocol.NOTIFY_THREAD_EXIT ->
        sendThreadEvent(ThreadEvent.REASON_EXITED, notification.params().path("threadId").asInt());
      default -> System.err.println("HxcppDebugAdapter: unknown notification '" + notification.method() + "'");
    }
  }

  private void sendStopped(String reason, int threadId, String description) throws IOException {
    stoppedThreadId = threadId;
    variablePaths.clear(); // the server invalidates its references on every stop
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
    variablePaths.clear();
  }

  // ------------------------------------------------------------------ helpers

  /**
   * A frame source usable by the IDE, or null. The server reports "?" (in
   * various path-mangled forms) for native/unknown frames — anything that is
   * not a valid file path yields a frame without source, not an error.
   */
  private static Source toSource(String sourcePath) {
    if (sourcePath == null || sourcePath.isEmpty()) {
      return null;
    }
    try {
      Source source = new Source();
      source.setPath(sourcePath);
      Path fileName = Path.of(sourcePath).getFileName();
      source.setName(fileName != null ? fileName.toString() : sourcePath);
      return source;
    } catch (java.nio.file.InvalidPathException e) {
      return null;
    }
  }

  private static Variable toVariable(HxcppVarInfo varInfo) {
    Variable variable = new Variable();
    variable.setName(varInfo.name());
    variable.setValue(varInfo.value());
    variable.setType(varInfo.type());
    variable.setVariablesReference(varInfo.variablesReference());
    variable.setNamedVariables(varInfo.namedVariables());
    variable.setIndexedVariables(varInfo.indexedVariables());
    return variable;
  }

  private static <T> T decode(JsonNode result, Class<T> type) {
    if (result == null || result.isNull()) {
      throw new IllegalStateException("Server returned no result where " + type.getSimpleName() + " was expected");
    }
    return JsonRpcJson.mapper().treeToValue(result, type);
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
    clientFuture.cancel(false);
    if (clientFuture.isDone() && !clientFuture.isCompletedExceptionally() && !clientFuture.isCancelled()) {
      try {
        clientFuture.get().close();
      } catch (Exception ignored) {
        // best-effort teardown
      }
    }
    debuggeeListener.close();
    if (dap != null) {
      dap.close();
    }
  }
}
