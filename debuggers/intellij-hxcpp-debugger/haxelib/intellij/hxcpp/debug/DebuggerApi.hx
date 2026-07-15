package intellij.hxcpp.debug;

/**
	Everything the server needs from `cpp.vm.Debugger`, behind an interface so
	the unit tests run under the interpreter against a scriptable fake (the
	native implementation is cpp-only). Data crosses as plain typedefs — the
	std ThreadInfo/StackFrame classes have (default,null) fields a fake could
	not construct.
**/
interface DebuggerApi {
	/** Marks the calling (server) thread as never-breaking. */
	function excludeCurrentThread():Void;

	/**
		Installs the runtime stop/lifecycle notification handler. CAUTION: the
		runtime invokes it on the STOPPING thread — implementations of the
		server must queue and return, never do protocol I/O inside it.
	**/
	function setEventHandler(handler:DebugEvent->Void):Void;

	/** All live threads with their current status and stacks. */
	function threads():Array<DebugThread>;

	/** Resumes `threadNumber` (-1 = all) `count` times. */
	function continueThreads(threadNumber:Int, count:Int):Void;
}

typedef DebugStackFrame = {
	var fileName:String;
	var lineNumber:Int;
	var className:String;
	var functionName:String;
}

typedef DebugThread = {
	var number:Int;
	var status:Int; // a ThreadStatus value
	var breakpoint:Int; // hit breakpoint number when status is STOPPED_BREAKPOINT
	var criticalErrorDescription:Null<String>;
	var stack:Array<DebugStackFrame>;
}

/** Mirrors cpp.vm.Debugger's ThreadInfo.STATUS_* values. */
class ThreadStatus {
	public static inline var RUNNING = 1;
	public static inline var STOPPED_BREAK_IMMEDIATE = 2;
	public static inline var STOPPED_BREAKPOINT = 3;
	public static inline var STOPPED_UNCAUGHT_EXCEPTION = 4;
	public static inline var STOPPED_CRITICAL_ERROR = 5;
}

/** A runtime notification, re-delivered on the server thread. */
enum DebugEvent {
	ThreadCreated(threadNumber:Int);
	ThreadTerminated(threadNumber:Int);
	ThreadStarted(threadNumber:Int);
	// `status` is the ThreadStatus explaining WHY (breakpoint/exception/pause)
	ThreadStopped(threadNumber:Int, status:Int, frame:Null<DebugStackFrame>);
}
