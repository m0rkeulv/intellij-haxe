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
	// the currently-stopped thread's stack (innermost-last), captured on the
	// stopping thread and cached to serve stackTrace (and scopes/variables in M4)
	var stoppedStack:Null<Array<DebugStackFrame>> = null;

	// An in-flight source-level step: hxcpp stops on the SAME line for multi-
	// expression lines (and never re-fires a loop-body line), so a step keeps
	// re-issuing until the source line actually changes — the DAP-level "step
	// until the line changes" policy. Bounded so a pathological program can
	// never step forever.
	static inline var MAX_STEP_ITERATIONS = 100000;
	var stepActive:Bool = false;
	var stepType:Int = 0;
	var stepFromFile:String = "";
	var stepFromLine:Int = 0;
	var stepIterations:Int = 0;

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
				// hxcpp's continueThreads wants the stopped thread as its "special"
				// arg (count 1 = stop at the next breakpoint), not a wildcard.
				stepActive = false;
				debugger.continueThreads(resumeThread(request.arguments), 1);
				sendResponse(seq, command, true, {allThreadsContinued: true});
			case "pause":
				// break the world; the resulting BREAK_IMMEDIATE stop is reported
				// as reason "pause" (no step is in flight)
				stepActive = false;
				debugger.breakNow(false);
				sendResponse(seq, command, true, null);
			case "next":
				handleStep(seq, command, request.arguments, StepType.OVER);
			case "stepIn":
				handleStep(seq, command, request.arguments, StepType.INTO);
			case "stepOut":
				handleStep(seq, command, request.arguments, StepType.OUT);
			case "stackTrace":
				handleStackTrace(seq, command, request.arguments);
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

	// The thread a resume/step/stackTrace acts on: the request's threadId, else
	// the last stop (a DAP client always names one, but stay safe).
	function resumeThread(args:Dynamic):Int {
		return (args != null && args.threadId != null) ? args.threadId : lastStoppedThread;
	}

	// Begins a source-level step. Records the current line so the stop policy can
	// re-step until it changes (see emitDebugEvent). Responds immediately; the
	// stopped(reason:"step") event follows when the step lands.
	function handleStep(seq:Int, command:String, args:Dynamic, type:Int):Void {
		var threadId = resumeThread(args);
		var from = topFrame(stoppedStack);
		stepActive = true;
		stepType = type;
		stepFromFile = from != null ? from.fileName : "";
		stepFromLine = from != null ? from.lineNumber : 0;
		stepIterations = 0;
		debugger.stepThread(threadId, type);
		sendResponse(seq, command, true, null);
	}

	function handleStackTrace(seq:Int, command:String, args:Dynamic):Void {
		var frames:Array<Dynamic> = [];
		if (stoppedStack != null) {
			var stack = stoppedStack;
			// hxcpp orders the stack innermost-LAST; DAP wants the newest frame
			// first, so walk it in reverse. The frame id is its stack index for
			// now (M4 builds a proper per-stop registry for scopes/variables).
			var i = stack.length - 1;
			while (i >= 0) {
				var frame = stack[i];
				var source:Dynamic = {name: baseName(frame.fileName), path: fullPathFor(frame.fileName)};
				frames.push({
					id: i,
					name: frame.className + "." + frame.functionName,
					line: frame.lineNumber,
					column: 1,
					source: source
				});
				i--;
			}
		}
		sendResponse(seq, command, true, {stackFrames: frames, totalFrames: frames.length});
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
				// a thread RESUMED (runtime "started" = running again); DAP resume
				// reporting is implicit in our continue/step responses
			case ThreadStopped(threadNumber, status, breakpoint, stack):
				handleThreadStopped(threadNumber, status, breakpoint, stack);
		}
	}

	function handleThreadStopped(threadNumber:Int, status:Int, breakpoint:Int, stack:Array<DebugStackFrame>):Void {
		lastStoppedThread = threadNumber;
		stoppedStack = stack;

		// A step landing that did not change the source line: re-issue the step
		// (multi-expression line, or a loop-body line that never "changes"), up
		// to the safety cap. Only for a plain step landing (BREAK_IMMEDIATE) —
		// a breakpoint or exception hit mid-step wins and is reported.
		if (stepActive && status == DebugThread.STATUS_STOPPED_BREAK_IMMEDIATE) {
			var top = topFrame(stack);
			if (top != null && top.fileName == stepFromFile && top.lineNumber == stepFromLine
					&& stepIterations < MAX_STEP_ITERATIONS) {
				stepIterations++;
				debugger.stepThread(threadNumber, stepType);
				return; // keep stepping; no stopped event yet
			}
			stepActive = false;
			sendEvent("stopped", {reason: "step", threadId: threadNumber, allThreadsStopped: true});
			return;
		}

		// Any other stop ends a pending step.
		stepActive = false;
		var body:Dynamic = {
			reason: stopReason(status),
			threadId: threadNumber,
			allThreadsStopped: true
		};
		if (breakpoint >= 0) {
			var id = breakpoints.idForRuntimeNumber(breakpoint);
			if (id >= 0) {
				body.hitBreakpointIds = [id];
			}
		}
		sendEvent("stopped", body);
	}

	// STATUS_* -> DAP stopped reason. BREAK_IMMEDIATE that is NOT a step landing
	// is a user pause.
	static function stopReason(status:Int):String {
		return switch (status) {
			case DebugThread.STATUS_STOPPED_BREAKPOINT: "breakpoint";
			case DebugThread.STATUS_STOPPED_UNCAUGHT_EXCEPTION, DebugThread.STATUS_STOPPED_CRITICAL_ERROR: "exception";
			default: "pause";
		}
	}

	static inline function topFrame(stack:Null<Array<DebugStackFrame>>):Null<DebugStackFrame> {
		return (stack == null || stack.length == 0) ? null : stack[stack.length - 1]; // innermost is last
	}

	// The runtime file key (short "Main.hx") -> its absolute path, via the
	// index-aligned files()/filesFullPath() tables. Falls back to the key.
	var fullPaths:Null<Map<String, String>> = null;

	function fullPathFor(fileKey:String):String {
		if (fullPaths == null) {
			fullPaths = new Map();
			var files = debugger.files();
			var paths = debugger.filesFullPath();
			for (i in 0...files.length) {
				if (i < paths.length) {
					fullPaths.set(files[i], paths[i]);
				}
			}
		}
		var path = fullPaths.get(fileKey);
		return path != null ? path : fileKey;
	}

	static function baseName(path:String):String {
		var normalized = StringTools.replace(path, "\\", "/");
		var slash = normalized.lastIndexOf("/");
		return slash < 0 ? normalized : normalized.substr(slash + 1);
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
