package intellij.hxcpp.debug;

#if cpp
import cpp.vm.Debugger;
import intellij.hxcpp.debug.DebuggerApi;

/**
	The real DebuggerApi over `cpp.vm.Debugger`. Thin by design: the data
	types are typedef-aliased to the std classes, so runtime results pass
	through untouched.
**/
class NativeDebuggerApi implements DebuggerApi {
	public function new() {}

	public function excludeCurrentThread():Void {
		Debugger.enableCurrentThreadDebugging(false);
	}

	public function setEventHandler(handler:DebugEvent->Void):Void {
		Debugger.setEventNotificationHandler(
			(threadNumber:Int, event:Int, stackFrame:Int, className:String, functionName:String, fileName:String, lineNumber:Int) -> {
				// runs on the STOPPING thread: translate and hand off, nothing else.
				// The stop STATUS is not part of the callback; the Server resolves
				// it via threadStatus() on its own thread when dequeuing.
				if (event == Debugger.THREAD_CREATED) {
					handler(ThreadCreated(threadNumber));
				} else if (event == Debugger.THREAD_TERMINATED) {
					handler(ThreadTerminated(threadNumber));
				} else if (event == Debugger.THREAD_STARTED) {
					handler(ThreadStarted(threadNumber));
				} else if (event == Debugger.THREAD_STOPPED) {
					handler(ThreadStopped(threadNumber, DebugThread.STATUS_RUNNING,
						new DebugStackFrame(fileName, lineNumber, className, functionName)));
				}
			});
	}

	public function threads():Array<DebugThread> {
		return Debugger.getThreadInfos();
	}

	public function threadStatus(threadNumber:Int):Int {
		var info = Debugger.getThreadInfo(threadNumber, true);
		return info != null ? info.status : DebugThread.STATUS_RUNNING;
	}

	public function continueThreads(threadNumber:Int, count:Int):Void {
		Debugger.continueThreads(threadNumber, count);
	}
}
#end
