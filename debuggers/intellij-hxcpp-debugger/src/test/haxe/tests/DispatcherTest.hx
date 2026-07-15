package tests;

import haxe.Json;
import intellij.hxcpp.debug.DebuggerApi;
import intellij.hxcpp.debug.Dispatcher;

class DispatcherTest {
	public static function run(assert:Assert):Void {
		initializeRespondsThenEmitsInitialized(assert);
		threadsComeFromTheDebuggerApi(assert);
		disconnectAcksAndRequestsShutdown(assert);
		unknownCommandFailsWithoutKillingTheSession(assert);
		invalidJsonFailsGracefully(assert);
		runtimeEventsMapToDapEvents(assert);
		setBreakpointsInstallsAndReturnsResults(assert);
		aBreakpointStopCarriesTheHitId(assert);
		continueResumesAllThreads(assert);
		eventsBeforeInitializeAreBufferedThenFlushed(assert);
	}

	static function eventsBeforeInitializeAreBufferedThenFlushed(assert:Assert):Void {
		var t = make();
		// a debuggee whose main thread already exists fires this immediately
		t.dispatcher.handleDebugEvent(ThreadCreated(1));
		assert.equals(0, t.sent.length, "nothing emitted before initialize");
		t.dispatcher.handleRequest(request("initialize", 1));
		// response, initialized event, THEN the buffered thread event
		assert.equals("thread", t.sent[2].event, "buffered event flushed after initialized");
		assert.equals("started", t.sent[2].body.reason, "the buffered thread-created event");
	}

	static function continueResumesAllThreads(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleRequest(request("continue", 3));
		assert.isTrue(t.sent[0].success, "continue acked");
		assert.equals(1, t.api.continueCalls.length, "runtime resumed");
		assert.equals(-1, t.api.continueCalls[0].threadNumber, "all threads (-1)");
	}

	static function make():{dispatcher:Dispatcher, api:FakeDebuggerApi, sent:Array<Dynamic>} {
		var sent:Array<Dynamic> = [];
		var api = new FakeDebuggerApi();
		var dispatcher = new Dispatcher(api, payload -> sent.push(Json.parse(payload)));
		return {dispatcher: dispatcher, api: api, sent: sent};
	}

	static function request(command:String, seq:Int):String {
		return Json.stringify({seq: seq, type: "request", command: command});
	}

	static function initializeRespondsThenEmitsInitialized(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleRequest(request("initialize", 1));
		assert.equals(2, t.sent.length, "response plus initialized event");
		assert.equals("response", t.sent[0].type, "response first");
		assert.isTrue(t.sent[0].success, "initialize succeeds");
		assert.equals(1, t.sent[0].request_seq, "request seq echoed");
		assert.isTrue(t.sent[0].body.supportsConfigurationDoneRequest, "capabilities present");
		assert.equals("initialized", t.sent[1].event, "initialized event strictly after");
	}

	static function threadsComeFromTheDebuggerApi(assert:Assert):Void {
		var t = make();
		t.api.cannedThreads = [
			new DebugThread(1, DebugThread.STATUS_STOPPED_BREAKPOINT, 3),
			new DebugThread(5, DebugThread.STATUS_RUNNING)
		];
		t.dispatcher.handleRequest(request("threads", 7));
		var body = t.sent[0].body;
		assert.equals(2, body.threads.length, "both threads listed");
		assert.equals(5, body.threads[1].id, "thread numbers are the DAP ids");
	}

	static function disconnectAcksAndRequestsShutdown(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleRequest(request("disconnect", 9));
		assert.isTrue(t.sent[0].success, "disconnect acked");
		assert.isTrue(t.dispatcher.shutdownRequested, "shutdown requested");
	}

	static function unknownCommandFailsWithoutKillingTheSession(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleRequest(request("fancyNewThing", 3));
		assert.isTrue(t.sent[0].success == false, "unknown command fails");
		t.dispatcher.handleRequest(request("threads", 4));
		assert.isTrue(t.sent[1].success, "session still serves requests");
	}

	static function invalidJsonFailsGracefully(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleRequest("{not json");
		assert.isTrue(t.sent[0].success == false, "invalid JSON gets a failure response");
	}

	// Sends initialize and drops the response+initialized event, so a test can
	// assert on the events that follow (nothing is emitted before initialize).
	static function initialize(t:{dispatcher:Dispatcher, api:FakeDebuggerApi, sent:Array<Dynamic>}):Void {
		t.dispatcher.handleRequest(request("initialize", 1));
		t.sent.resize(0);
	}

	static function runtimeEventsMapToDapEvents(assert:Assert):Void {
		var t = make();
		initialize(t);
		t.dispatcher.handleDebugEvent(ThreadStopped(2, {status: DebugThread.STATUS_STOPPED_BREAKPOINT, breakpoint: -1}, null));
		t.dispatcher.handleDebugEvent(ThreadStopped(2, {status: DebugThread.STATUS_STOPPED_UNCAUGHT_EXCEPTION, breakpoint: -1}, null));
		t.dispatcher.handleDebugEvent(ThreadStopped(2, {status: DebugThread.STATUS_STOPPED_BREAK_IMMEDIATE, breakpoint: -1}, null));
		t.dispatcher.handleDebugEvent(ThreadCreated(4));
		t.dispatcher.handleDebugEvent(ThreadTerminated(4));
		assert.equals("breakpoint", t.sent[0].body.reason, "breakpoint stop reason");
		assert.equals("exception", t.sent[1].body.reason, "exception stop reason");
		assert.equals("pause", t.sent[2].body.reason, "immediate-break stop reason");
		assert.equals("started", t.sent[3].body.reason, "thread created");
		assert.equals("exited", t.sent[4].body.reason, "thread terminated");
		assert.isTrue(t.sent[0].body.allThreadsStopped, "hxcpp stops the world");
	}

	static function setBreakpointsInstallsAndReturnsResults(assert:Assert):Void {
		var t = make();
		t.api.cannedFilesFullPath = ["C:/build/src/Main.hx"];
		t.api.cannedFiles = ["Main.hx"];
		var payload = Json.stringify({
			seq: 1, type: "request", command: "setBreakpoints",
			arguments: {source: {path: "C:/build/src/Main.hx"}, breakpoints: [{line: 10}, {line: 12}]}
		});
		t.dispatcher.handleRequest(payload);
		assert.isTrue(t.sent[0].success, "setBreakpoints succeeds");
		assert.equals(2, t.sent[0].body.breakpoints.length, "one result per requested line");
		assert.isTrue(t.sent[0].body.breakpoints[0].verified, "verified against the matched file");
		assert.equals(2, t.api.installedBreakpoints.length, "installed in the runtime");
	}

	static function aBreakpointStopCarriesTheHitId(assert:Assert):Void {
		var t = make();
		initialize(t);
		t.api.cannedFilesFullPath = ["C:/build/src/Main.hx"];
		t.api.cannedFiles = ["Main.hx"];
		t.dispatcher.handleRequest(Json.stringify({
			seq: 1, type: "request", command: "setBreakpoints",
			arguments: {source: {path: "C:/build/src/Main.hx"}, breakpoints: [{line: 10}]}
		}));
		var runtimeNumber = t.api.installedBreakpoints[0].number;
		var dapId = t.sent[0].body.breakpoints[0].id;
		t.dispatcher.handleDebugEvent(ThreadStopped(1, {status: DebugThread.STATUS_STOPPED_BREAKPOINT, breakpoint: runtimeNumber}, null));
		var stopped = t.sent[1];
		assert.equals("breakpoint", stopped.body.reason, "breakpoint stop");
		assert.equals(dapId, stopped.body.hitBreakpointIds[0], "the hit id maps back to the DAP breakpoint");
	}
}
