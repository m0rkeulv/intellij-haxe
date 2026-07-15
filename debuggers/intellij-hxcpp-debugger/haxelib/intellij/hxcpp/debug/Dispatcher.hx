package intellij.hxcpp.debug;

import dap.protocol.SourceBreakpoint;
import dap.protocol.requests.SetBreakpointsArguments;
import haxe.Json;
import intellij.hxcpp.debug.DebuggerApi;
import intellij.hxcpp.debug.breakpoints.Breakpoints;

/**
	Translates decoded DAP request payloads into responses/events, written as
	JSON strings through the `send` sink (the transport frames them). Pure and
	synchronous — the Server owns threading and sockets — so every behaviour is
	unit-testable over a fake DebuggerApi.

	M1 surface: initialize/configurationDone/threads/disconnect plus the
	runtime-event -> DAP-event mapping. Breakpoints, run control, variables and
	evaluate arrive with their milestones.
**/
class Dispatcher {
	final debugger:DebuggerApi;
	final send:String->Void;
	final breakpoints:Breakpoints;
	var nextSeq:Int = 1;
	var nextBreakpointId:Int = 1;

	/** Set once a disconnect request was answered; the Server shuts down. */
	public var shutdownRequested(default, null):Bool = false;

	/** Set once the client finished configuration (breakpoints may arrive before). */
	public var configurationDone(default, null):Bool = false;

	// Debug events must not precede the `initialized` event (a debuggee whose
	// threads already exist fires THREAD_CREATED the moment debugging is enabled,
	// before the client even sends initialize). Buffer until initialize is
	// handled, then flush in arrival order.
	var initialized:Bool = false;
	final pendingEvents:Array<DebugEvent> = [];
	// the thread most recently reported stopped — hxcpp's continueThreads wants
	// the specific stopped thread as its "special" argument, not a wildcard
	var lastStoppedThread:Int = -1;

	public function new(debugger:DebuggerApi, send:String->Void) {
		this.debugger = debugger;
		this.send = send;
		this.breakpoints = new Breakpoints(debugger);
	}

	/**
		Handles one request payload. Never throws on bad input: a broken request
		gets a failure response and the session lives on.
	**/
	public function handleRequest(payload:String):Void {
		var request:Dynamic = try {
			Json.parse(payload);
		} catch (e:Dynamic) {
			sendResponse(0, "", false, null, "Invalid JSON payload");
			return;
		}
		var command:String = request.command;
		var seq:Int = request.seq != null ? request.seq : 0;
		if (request.type != "request" || command == null) {
			sendResponse(seq, command == null ? "" : command, false, null, "Not a valid DAP request");
			return;
		}
		switch (command) {
			case "initialize":
				sendResponse(seq, command, true, {
					supportsConfigurationDoneRequest: true
				});
				// the spec requires the initialized event strictly after the response
				sendEvent("initialized", null);
				initialized = true;
				for (event in pendingEvents) {
					emitDebugEvent(event);
				}
				pendingEvents.resize(0);
			case "setBreakpoints":
				handleSetBreakpoints(seq, command, request.arguments);
			case "continue":
				// minimal resume so a breakpoint stop can be released; pause and
				// step (and single-thread continue) are M3's run control. hxcpp's
				// continueThreads wants the stopped thread as its "special" arg —
				// prefer the request's threadId, fall back to the last stop.
				var threadId = (request.arguments != null && request.arguments.threadId != null)
					? request.arguments.threadId : lastStoppedThread;
				debugger.continueThreads(threadId, 1);
				sendResponse(seq, command, true, {allThreadsContinued: true});
			case "configurationDone":
				configurationDone = true;
				sendResponse(seq, command, true, null);
			case "threads":
				sendResponse(seq, command, true, {
					threads: [for (t in debugger.threads()) {id: t.number, name: "Thread " + t.number}]
				});
			case "disconnect":
				sendResponse(seq, command, true, null);
				shutdownRequested = true;
			default:
				sendResponse(seq, command, false, null, "Unrecognized command: " + command);
		}
	}

	// hxcpp replaces the whole breakpoint set for a source; assign each request
	// a stable DAP id and hand the batch to the Breakpoints manager.
	function handleSetBreakpoints(seq:Int, command:String, args:SetBreakpointsArguments):Void {
		var sourcePath = (args != null && args.source != null && args.source.path != null) ? args.source.path : "";
		var requested:Array<SourceBreakpoint> = (args != null && args.breakpoints != null) ? args.breakpoints : [];
		var ids = [for (_ in requested) nextBreakpointId++];
		var results = breakpoints.setForSource(sourcePath, requested, ids);
		sendResponse(seq, command, true, {breakpoints: results});
	}

	/**
		A runtime notification (already re-delivered on the server thread) turned
		into the matching DAP event — buffered until the initialize handshake so
		nothing precedes the `initialized` event.
	**/
	public function handleDebugEvent(event:DebugEvent):Void {
		if (!initialized) {
			pendingEvents.push(event);
			return;
		}
		emitDebugEvent(event);
	}

	function emitDebugEvent(event:DebugEvent):Void {
		switch (event) {
			case ThreadCreated(threadNumber):
				sendEvent("thread", {reason: "started", threadId: threadNumber});
			case ThreadTerminated(threadNumber):
				sendEvent("thread", {reason: "exited", threadId: threadNumber});
			case ThreadStarted(_):
				// a thread RESUMED (runtime "started" = running again); no DAP
				// continued event in M1 — run control owns resume reporting (M3)
			case ThreadStopped(threadNumber, stop, _):
				lastStoppedThread = threadNumber;
				var body:Dynamic = {
					reason: stopReason(stop.status),
					threadId: threadNumber,
					allThreadsStopped: true
				};
				if (stop.breakpoint >= 0) {
					var id = breakpoints.idForRuntimeNumber(stop.breakpoint);
					if (id >= 0) {
						body.hitBreakpointIds = [id];
					}
				}
				sendEvent("stopped", body);
		}
	}

	// STATUS_* -> DAP stopped reason. BREAK_IMMEDIATE covers both pause and
	// step landings in the runtime; M3 (run control) tells them apart by
	// tracking which one it asked for.
	static function stopReason(status:Int):String {
		return switch (status) {
			case DebugThread.STATUS_STOPPED_BREAKPOINT: "breakpoint";
			case DebugThread.STATUS_STOPPED_UNCAUGHT_EXCEPTION, DebugThread.STATUS_STOPPED_CRITICAL_ERROR: "exception";
			default: "pause";
		}
	}

	function sendResponse(requestSeq:Int, command:String, success:Bool, body:Dynamic, ?message:String):Void {
		var response:Dynamic = {
			seq: nextSeq++,
			type: "response",
			request_seq: requestSeq,
			success: success,
			command: command
		};
		if (body != null) {
			response.body = body;
		}
		if (message != null) {
			response.message = message;
		}
		send(Json.stringify(response));
	}

	function sendEvent(name:String, body:Dynamic):Void {
		var event:Dynamic = {
			seq: nextSeq++,
			type: "event",
			event: name
		};
		if (body != null) {
			event.body = body;
		}
		send(Json.stringify(event));
	}
}
