package tests;

import intellij.hxcpp.debug.DebuggerApi;

/**
	Scriptable DebuggerApi for the interpreter-run tests: canned thread lists,
	recorded calls, and manual event firing through the captured handler.
**/
class FakeDebuggerApi implements DebuggerApi {
	public var cannedThreads:Array<DebugThread> = [];
	public var continueCalls:Array<{threadNumber:Int, count:Int}> = [];
	public var excludedCurrentThread:Bool = false;
	public var handler:Null<DebugEvent->Void> = null;

	public function new() {}

	public function excludeCurrentThread():Void {
		excludedCurrentThread = true;
	}

	public function setEventHandler(handler:DebugEvent->Void):Void {
		this.handler = handler;
	}

	public function threads():Array<DebugThread> {
		return cannedThreads;
	}

	public function continueThreads(threadNumber:Int, count:Int):Void {
		continueCalls.push({threadNumber: threadNumber, count: count});
	}
}
