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

// The std debugger data types on cpp; API-identical stubs (one type per file,
// package intellij.hxcpp.debug.stubs) everywhere else.
#if cpp
typedef DebugParameter = cpp.vm.Debugger.Parameter;
typedef DebugStackFrame = cpp.vm.Debugger.StackFrame;
typedef DebugThread = cpp.vm.Debugger.ThreadInfo;
#else
typedef DebugParameter = intellij.hxcpp.debug.stubs.Parameter;
typedef DebugStackFrame = intellij.hxcpp.debug.stubs.StackFrame;
typedef DebugThread = intellij.hxcpp.debug.stubs.ThreadInfo;
#end
