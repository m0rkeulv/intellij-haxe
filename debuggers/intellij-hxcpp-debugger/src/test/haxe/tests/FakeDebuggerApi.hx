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

	public var enabledCurrentThread:Bool = false;

	public function enableCurrentThread():Void {
		enabledCurrentThread = true;
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

	public var breakNowCalls:Int = 0;

	public function breakNow(wait:Bool):Void {
		breakNowCalls++;
	}

	// breakpoint engine: canned file tables, recorded installs, monotonic numbers
	public var cannedFiles:Array<String> = [];
	public var cannedFilesFullPath:Array<String> = [];
	public var installedBreakpoints:Array<{file:String, line:Int, number:Int}> = [];
	public var deletedBreakpoints:Array<Int> = [];
	var nextBreakpointNumber:Int = 100;

	public function files():Array<String> {
		return cannedFiles;
	}

	public function filesFullPath():Array<String> {
		return cannedFilesFullPath;
	}

	public function addFileLineBreakpoint(file:String, line:Int):Int {
		var number = nextBreakpointNumber++;
		installedBreakpoints.push({file: file, line: line, number: number});
		return number;
	}

	public function deleteBreakpoint(number:Int):Void {
		deletedBreakpoints.push(number);
	}
}
