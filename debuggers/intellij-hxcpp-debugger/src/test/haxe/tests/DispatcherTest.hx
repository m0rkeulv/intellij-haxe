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
			{number: 1, status: ThreadStatus.STOPPED_BREAKPOINT, breakpoint: 3, criticalErrorDescription: null, stack: []},
			{number: 5, status: ThreadStatus.RUNNING, breakpoint: -1, criticalErrorDescription: null, stack: []}
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

	static function runtimeEventsMapToDapEvents(assert:Assert):Void {
		var t = make();
		t.dispatcher.handleDebugEvent(ThreadStopped(2, ThreadStatus.STOPPED_BREAKPOINT, null));
		t.dispatcher.handleDebugEvent(ThreadStopped(2, ThreadStatus.STOPPED_UNCAUGHT_EXCEPTION, null));
		t.dispatcher.handleDebugEvent(ThreadStopped(2, ThreadStatus.STOPPED_BREAK_IMMEDIATE, null));
		t.dispatcher.handleDebugEvent(ThreadCreated(4));
		t.dispatcher.handleDebugEvent(ThreadTerminated(4));
		assert.equals("breakpoint", t.sent[0].body.reason, "breakpoint stop reason");
		assert.equals("exception", t.sent[1].body.reason, "exception stop reason");
		assert.equals("pause", t.sent[2].body.reason, "immediate-break stop reason");
		assert.equals("started", t.sent[3].body.reason, "thread created");
		assert.equals("exited", t.sent[4].body.reason, "thread terminated");
		assert.isTrue(t.sent[0].body.allThreadsStopped, "hxcpp stops the world");
	}
}
