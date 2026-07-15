package intellij.hxcpp.debug;

import haxe.Json;
import intellij.hxcpp.debug.DebuggerApi;

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
	var nextSeq:Int = 1;

	/** Set once a disconnect request was answered; the Server shuts down. */
	public var shutdownRequested(default, null):Bool = false;

	/** Set once the client finished configuration (breakpoints may arrive before). */
	public var configurationDone(default, null):Bool = false;

	public function new(debugger:DebuggerApi, send:String->Void) {
		this.debugger = debugger;
		this.send = send;
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

	/**
		A runtime notification (already re-delivered on the server thread) turned
		into the matching DAP event.
	**/
	public function handleDebugEvent(event:DebugEvent):Void {
		switch (event) {
			case ThreadCreated(threadNumber):
				sendEvent("thread", {reason: "started", threadId: threadNumber});
			case ThreadTerminated(threadNumber):
				sendEvent("thread", {reason: "exited", threadId: threadNumber});
			case ThreadStarted(_):
				// a thread RESUMED (runtime "started" = running again); no DAP
				// continued event in M1 — run control owns resume reporting (M3)
			case ThreadStopped(threadNumber, status, _):
				sendEvent("stopped", {
					reason: stopReason(status),
					threadId: threadNumber,
					allThreadsStopped: true
				});
		}
	}

	// ThreadStatus -> DAP stopped reason. BREAK_IMMEDIATE covers both pause and
	// step landings in the runtime; M3 (run control) tells them apart by
	// tracking which one it asked for.
	static function stopReason(status:Int):String {
		return switch (status) {
			case ThreadStatus.STOPPED_BREAKPOINT: "breakpoint";
			case ThreadStatus.STOPPED_UNCAUGHT_EXCEPTION, ThreadStatus.STOPPED_CRITICAL_ERROR: "exception";
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
