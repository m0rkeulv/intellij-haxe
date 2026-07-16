package intellij.hxcpp.debug;

import dap.protocol.SourceBreakpoint;
import dap.protocol.requests.SetBreakpointsArguments;
import haxe.Json;
import intellij.hxcpp.debug.DebuggerApi;
import intellij.hxcpp.debug.breakpoints.Breakpoints;
import intellij.hxcpp.debug.eval.Evaluator;
import intellij.hxcpp.debug.values.VariablesView;

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
	final variablesView:VariablesView;
	final evaluator:Evaluator;
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
	// Stacks (innermost-last) of EVERY currently-stopped thread, captured on
	// each stopping thread — a pause stops them all, and the client asks for
	// each one's stackTrace. Entries leave when their thread resumes.
	final stoppedStacks = new Map<Int, Array<DebugStackFrame>>();
	// DAP frameId -> (thread, hxcpp stack index). Frame ids must encode the
	// thread because scopes/evaluate only receive a frameId. Valid until the
	// next resume: references die when the program moves, not when another
	// thread of the same stop-burst reports in.
	var nextFrameId:Int = 1;
	final framesById = new Map<Int, {thread:Int, index:Int}>();

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

	// Smart step into (custom request "intellij/stepIntoFunction"): a TEMPORARY
	// class-function breakpoint at the chosen callee's entry races a STEP_OVER —
	// whichever lands first is the stop, reported as a plain step. -1 = none.
	// The temp lives outside the user-breakpoint bookkeeping (no DAP id, no
	// condition), and dies with the next reported stop or resume.
	var tempStepBreakpoint:Int = -1;

	// Exception filters (both default ON, mirroring the advertised defaults —
	// a client that never sends setExceptionBreakpoints gets the defaults) and
	// the last exception stop, kept for the exceptionInfo request.
	static inline var FILTER_UNCAUGHT = "uncaught";
	static inline var FILTER_CRITICAL = "critical";
	var breakOnUncaught:Bool = true;
	var breakOnCritical:Bool = true;
	var lastExceptionDescription:Null<String> = null;
	var lastExceptionKind:Null<String> = null;

	// Resuming a critical error usually re-faults on the spot (the runtime's
	// "fixup" path re-executes the null access -> segv -> stop again; observed
	// live), so auto-resuming with the filter off would livelock the program.
	// After a few consecutive silent resumes the stop is reported regardless.
	static inline var MAX_SILENT_CRITICAL_RESUMES = 3;
	var silentCriticalResumes:Int = 0;

	public function new(debugger:DebuggerApi, send:String->Void) {
		this.debugger = debugger;
		this.send = send;
		this.breakpoints = new Breakpoints(debugger);
		this.variablesView = new VariablesView(debugger);
		this.evaluator = new Evaluator(debugger);
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
		try {
			dispatch(seq, command, request);
		} catch (e:Dynamic) {
			// FAULT ISOLATION: one faulting handler must not kill the session.
			// Real debuggees fault their readers — a corrupt frame slot raises a
			// critical error which hxcpp RE-THROWS on this (the debug) thread as
			// "Critical Error in the debugger thread". Without this catch that
			// throw unwound into the Server's wire-death catch and the server
			// silently stopped serving: every later request timed out and resume
			// never happened. Answer with the error and keep serving. (A hard
			// segfault still kills the process; nothing catches that.)
			sendResponse(seq, command, false, null, "Internal debugger error: " + Std.string(e));
		}
	}

	function dispatch(seq:Int, command:String, request:Dynamic):Void {
		switch (command) {
			case "initialize":
				sendResponse(seq, command, true, {
					supportsConfigurationDoneRequest: true,
					supportsConditionalBreakpoints: true,
					supportsEvaluateForHovers: true,
					supportsSetVariable: true,
					supportsExceptionInfoRequest: true,
					// both kinds arrive from the runtime as CRITICAL_ERROR stops and
					// are told apart by description (see exceptionKind); "break on
					// caught exceptions" has no runtime hook — docs/README
					exceptionBreakpointFilters: [
						{
							filter: FILTER_UNCAUGHT,
							label: "Uncaught exceptions",
							description: "Break where a value is thrown that no enclosing try/catch can catch (before unwinding).",
							'default': true
						},
						{
							filter: FILTER_CRITICAL,
							label: "Critical errors",
							description: "Break on runtime critical errors (null access, GC errors). Under a debugger these stop even inside try/catch.",
							'default': true
						}
					]
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
			case "setExceptionBreakpoints":
				handleSetExceptionBreakpoints(seq, command, request.arguments);
			case "exceptionInfo":
				handleExceptionInfo(seq, command);
			case "continue":
				// hxcpp's continueThreads wants the stopped thread as its "special"
				// arg (count 1 = stop at the next breakpoint), not a wildcard. It
				// resumes EVERY stopped thread, hence allThreadsContinued.
				stepActive = false;
				clearTempStepBreakpoint();
				resumed();
				stoppedStacks.clear();
				debugger.continueThreads(resumeThread(request.arguments), 1);
				sendResponse(seq, command, true, {allThreadsContinued: true});
			case "intellij/stepIntoFunction":
				handleStepIntoFunction(seq, command, request.arguments);
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
			case "scopes":
				handleScopes(seq, command, request.arguments);
			case "variables":
				var reference = (request.arguments != null && request.arguments.variablesReference != null)
					? request.arguments.variablesReference : 0;
				sendResponse(seq, command, true, {variables: variablesView.variables(reference)});
			case "setVariable":
				handleSetVariable(seq, command, request.arguments);
			case "evaluate":
				handleEvaluate(seq, command, request.arguments);
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

	// Every resume path funnels through here: inspection references (variables
	// and frame ids) die when the program moves.
	function resumed():Void {
		variablesView.reset();
		framesById.clear();
		nextFrameId = 1;
	}

	// Begins a source-level step. Records the current line so the stop policy can
	// re-step until it changes (see emitDebugEvent). Responds immediately; the
	// stopped(reason:"step") event follows when the step lands.
	function handleStep(seq:Int, command:String, args:Dynamic, type:Int):Void {
		clearTempStepBreakpoint(); // a fresh user step cancels a pending smart step
		var threadId = resumeThread(args);
		var from = topFrame(stoppedStacks.get(threadId));
		stepActive = true;
		stepType = type;
		stepFromFile = from != null ? from.fileName : "";
		stepFromLine = from != null ? from.lineNumber : 0;
		stepIterations = 0;
		resumed();
		stoppedStacks.remove(threadId); // stepThread resumes only this thread
		debugger.stepThread(threadId, type);
		sendResponse(seq, command, true, null);
	}

	/**
		Smart step into: enter the CHOSEN call on the stopped line. The IDE
		resolves the line's calls through its PSI (the server has no line→calls
		knowledge — there is no bytecode to mine on hxcpp) and names the callee
		as (className, functionName). A temporary entry breakpoint on the callee
		races an ordinary step-over: entering the callee lands the temp (running
		through earlier calls on the line); if the chosen call never executes
		(short-circuit, conditional), the step-over lands instead — degrading to
		a plain step over, exactly like the HashLink implementation. Either
		landing is reported as reason "step".
	**/
	function handleStepIntoFunction(seq:Int, command:String, args:Dynamic):Void {
		if (args == null || args.className == null || args.functionName == null) {
			sendResponse(seq, command, false, null, "Missing className/functionName");
			return;
		}
		clearTempStepBreakpoint(); // replace any previous pending smart step
		var threadId = resumeThread(args);
		var from = topFrame(stoppedStacks.get(threadId));
		var number = debugger.addClassFunctionBreakpoint(args.className, args.functionName);
		if (number < 0) {
			// The runtime REJECTED the class name (hxcpp validates it against its
			// compiled-in class table; -1 arms nothing — and per gotcha 8, with no
			// live breakpoint the per-line hook stays disarmed and a step can run
			// unchecked forever). Do not gamble with the user's session: answer
			// and re-report the current stop — a visible no-op the user can
			// follow with a plain step.
			sendResponse(seq, command, true, null);
			sendEvent("stopped", {reason: "step", threadId: threadId, allThreadsStopped: true});
			return;
		}
		tempStepBreakpoint = number;
		// bookkeep exactly like a step-over: the same-line re-step policy keeps
		// the step racing while the temp stays armed
		stepActive = true;
		stepType = StepType.OVER;
		stepFromFile = from != null ? from.fileName : "";
		stepFromLine = from != null ? from.lineNumber : 0;
		stepIterations = 0;
		resumed();
		stoppedStacks.remove(threadId);
		debugger.stepThread(threadId, StepType.OVER);
		sendResponse(seq, command, true, null);
	}

	function clearTempStepBreakpoint():Void {
		if (tempStepBreakpoint >= 0) {
			debugger.deleteBreakpoint(tempStepBreakpoint);
			tempStepBreakpoint = -1;
		}
	}

	function handleStackTrace(seq:Int, command:String, args:Dynamic):Void {
		var threadId = resumeThread(args);
		var stack = stoppedStacks.get(threadId);
		var frames:Array<Dynamic> = [];
		if (stack != null) {
			// hxcpp orders the stack innermost-LAST; DAP wants the newest frame
			// first, so walk it in reverse. Each frame gets a registry id that
			// remembers its (thread, index) — scopes/evaluate only get the id.
			var i = stack.length - 1;
			while (i >= 0) {
				var frame = stack[i];
				var id = nextFrameId++;
				framesById.set(id, {thread: threadId, index: i});
				var source:Dynamic = {name: baseName(frame.fileName), path: fullPathFor(frame.fileName)};
				frames.push({
					id: id,
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

	// A frame's Locals scope. The frameId names a (thread, stack index) pair via
	// the registry built in stackTrace. hxcpp exposes one flat set of locals per
	// frame (params + declared vars + `this`), so we surface a single "Locals"
	// scope rather than splitting arguments out.
	function handleScopes(seq:Int, command:String, args:Dynamic):Void {
		var frameId = (args != null && args.frameId != null) ? args.frameId : 0;
		var location = framesById.get(frameId);
		if (location == null) {
			sendResponse(seq, command, false, null, "Unknown or stale frameId " + frameId);
			return;
		}
		var reference = variablesView.frameScope(location.thread, location.index);
		sendResponse(seq, command, true, {
			scopes: [{name: "Locals", variablesReference: reference, expensive: false}]
		});
	}

	// evaluate a watch/hover/repl expression against a frame; a bare assignment
	// writes back to the debuggee. The frameId comes from stackTrace's registry;
	// a frameless evaluate targets the last stop's innermost frame.
	function handleEvaluate(seq:Int, command:String, args:Dynamic):Void {
		if (args == null || args.expression == null) {
			sendResponse(seq, command, false, null, "Missing expression");
			return;
		}
		var location = (args.frameId != null) ? framesById.get(args.frameId) : null;
		var thread = location != null ? location.thread : lastStoppedThread;
		var frame;
		if (location != null) {
			frame = location.index;
		} else {
			// default to the innermost frame (highest hxcpp index, innermost-last)
			var stack = stoppedStacks.get(lastStoppedThread);
			frame = stack != null ? stack.length - 1 : 0;
		}
		try {
			var value = evaluator.evaluate(thread, frame, args.expression);
			var presented = variablesView.present(value);
			sendResponse(seq, command, true, {result: presented.value, type: presented.type, variablesReference: presented.variablesReference});
		} catch (e:Dynamic) {
			sendResponse(seq, command, false, null, Std.string(e));
		}
	}

	function handleSetVariable(seq:Int, command:String, args:Dynamic):Void {
		if (args == null || args.variablesReference == null || args.name == null || args.value == null) {
			sendResponse(seq, command, false, null, "Missing variablesReference, name or value");
			return;
		}
		var result = variablesView.setVariable(args.variablesReference, args.name, args.value);
		if (result == null) {
			sendResponse(seq, command, false, null, "Cannot set '" + args.name + "': unknown or stale reference");
			return;
		}
		sendResponse(seq, command, true, result);
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
			case ThreadStarted(threadNumber):
				// a thread RESUMED (runtime "started" = running again); its stack
				// is stale now. DAP resume reporting is implicit in our
				// continue/step responses.
				stoppedStacks.remove(threadNumber);
			case ThreadStopped(threadNumber, status, breakpoint, stack, description):
				handleThreadStopped(threadNumber, status, breakpoint, stack, description);
		}
	}

	function handleThreadStopped(threadNumber:Int, status:Int, breakpoint:Int, stack:Array<DebugStackFrame>, description:Null<String>):Void {
		lastStoppedThread = threadNumber;
		stoppedStacks.set(threadNumber, stack);
		// NO reference reset here: a pause stops every thread and their stop
		// events arrive as a burst — resetting per event would invalidate frame
		// ids the client just received for a sibling thread. References die on
		// resume instead (resumed()).
		lastExceptionDescription = null;
		lastExceptionKind = null;

		// A step landing that did not change the source line: re-issue the step
		// (multi-expression line, or a loop-body line that never "changes"), up
		// to the safety cap. Only for a plain step landing (BREAK_IMMEDIATE) —
		// a breakpoint or exception hit mid-step wins and is reported.
		if (stepActive && status == DebugThread.STATUS_STOPPED_BREAK_IMMEDIATE) {
			var top = topFrame(stack);
			if (top != null && top.fileName == stepFromFile && top.lineNumber == stepFromLine
					&& stepIterations < MAX_STEP_ITERATIONS) {
				stepIterations++;
				stoppedStacks.remove(threadNumber);
				debugger.stepThread(threadNumber, stepType);
				return; // keep stepping; no stopped event yet
			}
			stepActive = false;
			clearTempStepBreakpoint(); // the step-over won the smart-step race
			sendEvent("stopped", {reason: "step", threadId: threadNumber, allThreadsStopped: true});
			return;
		}

		// Any other stop ends a pending step.
		stepActive = false;

		// The smart-step temp landing: the chosen callee's entry. Reported as a
		// plain step stop; any OTHER stop (user breakpoint, exception, pause)
		// wins the race and reports normally — either way the temp dies here.
		if (tempStepBreakpoint >= 0) {
			var enteredTarget = status == DebugThread.STATUS_STOPPED_BREAKPOINT && breakpoint == tempStepBreakpoint;
			clearTempStepBreakpoint();
			if (enteredTarget) {
				sendEvent("stopped", {reason: "step", threadId: threadNumber, allThreadsStopped: true});
				return;
			}
		}

		// An exception/critical-error stop: the thread is blocked AT the throw
		// site (before unwinding), so the full stack and locals are inspectable.
		// A disabled filter resumes silently; the runtime then unwinds/terminates
		// exactly as it would have without the stop.
		if (status == DebugThread.STATUS_STOPPED_UNCAUGHT_EXCEPTION || status == DebugThread.STATUS_STOPPED_CRITICAL_ERROR) {
			var kind = exceptionKind(description);
			var enabled = kind == FILTER_UNCAUGHT ? breakOnUncaught : breakOnCritical;
			if (!enabled) {
				// resuming an uncatchable throw unwinds/terminates cleanly; a
				// critical error re-faults, so cap the silent resumes (livelock)
				if (kind != FILTER_CRITICAL || silentCriticalResumes < MAX_SILENT_CRITICAL_RESUMES) {
					if (kind == FILTER_CRITICAL) {
						silentCriticalResumes++;
					}
					resumed();
					stoppedStacks.clear(); // continueThreads resumes every thread
					debugger.continueThreads(threadNumber, 1);
					return;
				}
			}
			silentCriticalResumes = 0;
			lastExceptionDescription = description != null ? description : "Exception";
			lastExceptionKind = kind;
			sendEvent("stopped", {
				reason: "exception",
				threadId: threadNumber,
				allThreadsStopped: true,
				description: kind == FILTER_UNCAUGHT ? "Uncaught exception" : "Critical error",
				text: lastExceptionDescription
			});
			return;
		}

		// A conditional breakpoint stops only when its condition is true; a false
		// condition resumes silently. Evaluated against the INNERMOST frame,
		// which is the highest hxcpp frame index (stack is innermost-last).
		if (status == DebugThread.STATUS_STOPPED_BREAKPOINT && breakpoint >= 0) {
			var condition = breakpoints.conditionForRuntimeNumber(breakpoint);
			if (condition != null && condition != "" && !evaluator.conditionHolds(threadNumber, stack.length - 1, condition)) {
				resumed();
				stoppedStacks.clear(); // continueThreads resumes every thread
				debugger.continueThreads(threadNumber, 1);
				return;
			}
		}

		silentCriticalResumes = 0; // any reported stop means the program moved on
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
	// is a user pause (exception statuses are handled before this is consulted).
	static function stopReason(status:Int):String {
		return switch (status) {
			case DebugThread.STATUS_STOPPED_BREAKPOINT: "breakpoint";
			default: "pause";
		}
	}

	// Which filter a stop belongs to. The runtime reports BOTH kinds as
	// CRITICAL_ERROR (STATUS_STOPPED_UNCAUGHT_EXCEPTION is never emitted by
	// hxcpp 4.3.2 — verified by source grep); an uncatchable user throw is
	// distinguished by checkedThrow's "Uncatchable Throw: <value>" prefix.
	static function exceptionKind(description:Null<String>):String {
		return (description != null && StringTools.startsWith(description, "Uncatchable Throw"))
			? FILTER_UNCAUGHT : FILTER_CRITICAL;
	}

	// DAP sends the full ACTIVE filter list each time (an omitted filter is off).
	function handleSetExceptionBreakpoints(seq:Int, command:String, args:Dynamic):Void {
		var filters:Array<String> = (args != null && args.filters != null) ? args.filters : [];
		breakOnUncaught = filters.indexOf(FILTER_UNCAUGHT) >= 0;
		breakOnCritical = filters.indexOf(FILTER_CRITICAL) >= 0;
		sendResponse(seq, command, true, {breakpoints: [for (_ in filters) {verified: true}]});
	}

	function handleExceptionInfo(seq:Int, command:String):Void {
		if (lastExceptionDescription == null) {
			sendResponse(seq, command, false, null, "Not stopped on an exception");
			return;
		}
		sendResponse(seq, command, true, {
			exceptionId: lastExceptionKind == FILTER_UNCAUGHT ? "Uncaught exception" : "Critical error",
			description: lastExceptionDescription,
			// uncaught throws could not have been handled; critical errors stop
			// unconditionally (even inside try/catch), hence "always"
			breakMode: lastExceptionKind == FILTER_UNCAUGHT ? "unhandled" : "always"
		});
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
