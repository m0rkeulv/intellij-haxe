package intellij.hxcpp.debug;

/**
	Everything the server needs from `cpp.vm.Debugger`, behind an interface so
	the unit tests run under the interpreter against a scriptable fake (the
	native implementation is cpp-only).

	The data types alias the std classes DIRECTLY on the cpp target — the
	native implementation hands `getThreadInfos()` results over untouched and
	upstream std evolution is tracked for free. Every other target (the
	interpreter running the unit tests) gets API-identical stubs below,
	because the compiler rejects the cpp package there ("You cannot access
	the cpp package while targeting eval"). Keep the stubs' surface exactly
	in sync with std `cpp/vm/Debugger.hx`; the cpp compile of any fixture
	cross-checks shared code against the real types.
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

/** A runtime notification, re-delivered on the server thread. */
enum DebugEvent {
	ThreadCreated(threadNumber:Int);
	ThreadTerminated(threadNumber:Int);
	ThreadStarted(threadNumber:Int);
	// `status` is a DebugThread.STATUS_* value explaining WHY
	ThreadStopped(threadNumber:Int, status:Int, frame:Null<DebugStackFrame>);
}

#if cpp
typedef DebugParameter = cpp.vm.Debugger.Parameter;
typedef DebugStackFrame = cpp.vm.Debugger.StackFrame;
typedef DebugThread = cpp.vm.Debugger.ThreadInfo;
#else

/** Non-cpp stand-in for `cpp.vm.Debugger.Parameter` — same API surface. */
class DebugParameter {
	public var name(default, null):String;
	public var value(default, null):Dynamic;

	public function new(name:String, value:Dynamic) {
		this.name = name;
		this.value = value;
	}
}

/** Non-cpp stand-in for `cpp.vm.Debugger.StackFrame` — same API surface. */
class DebugStackFrame {
	public var fileName(default, null):String;
	public var lineNumber(default, null):Int;
	public var className(default, null):String;
	public var functionName(default, null):String;
	public var parameters(default, null):Array<DebugParameter> = [];

	public function new(fileName:String, lineNumber:Int, className:String, functionName:String) {
		this.fileName = fileName;
		this.lineNumber = lineNumber;
		this.className = className;
		this.functionName = functionName;
	}
}

/** Non-cpp stand-in for `cpp.vm.Debugger.ThreadInfo` — same API surface. */
class DebugThread {
	public static inline var STATUS_RUNNING = 1;
	public static inline var STATUS_STOPPED_BREAK_IMMEDIATE = 2;
	public static inline var STATUS_STOPPED_BREAKPOINT = 3;
	public static inline var STATUS_STOPPED_UNCAUGHT_EXCEPTION = 4;
	public static inline var STATUS_STOPPED_CRITICAL_ERROR = 5;

	public var number(default, null):Int;
	public var status(default, null):Int;
	public var breakpoint(default, null):Int;
	public var criticalErrorDescription(default, null):String;
	public var stack(default, null):Array<DebugStackFrame> = [];

	public function new(number:Int, status:Int, breakpoint:Int = -1, criticalErrorDescription:String = null) {
		this.number = number;
		this.status = status;
		this.breakpoint = breakpoint;
		this.criticalErrorDescription = criticalErrorDescription;
	}
}
#end
