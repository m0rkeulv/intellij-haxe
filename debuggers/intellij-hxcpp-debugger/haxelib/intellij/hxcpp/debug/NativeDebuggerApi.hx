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

	public function enableCurrentThread():Void {
		Debugger.enableCurrentThreadDebugging(true);
	}

	public function setEventHandler(handler:DebugEvent->Void):Void {
		Debugger.setEventNotificationHandler(
			(threadNumber:Int, event:Int, stackFrame:Int, className:String, functionName:String, fileName:String, lineNumber:Int) -> {
				// Runs on the STOPPING thread. The stop STATUS is read here, where
				// the thread is guaranteed stopped (safe=false), not later from the
				// server thread — a cross-thread read races the thread's state and
				// returns RUNNING. Then hand off and return; hxcpp blocks the
				// thread in DoBreak until continueThreads.
				if (event == Debugger.THREAD_CREATED) {
					handler(ThreadCreated(threadNumber));
				} else if (event == Debugger.THREAD_TERMINATED) {
					handler(ThreadTerminated(threadNumber));
				} else if (event == Debugger.THREAD_STARTED) {
					handler(ThreadStarted(threadNumber));
				} else if (event == Debugger.THREAD_STOPPED) {
					var info = Debugger.getThreadInfo(threadNumber, false);
					var stop = {status: info != null ? info.status : DebugThread.STATUS_RUNNING,
						breakpoint: info != null ? info.breakpoint : -1};
					handler(ThreadStopped(threadNumber, stop,
						new DebugStackFrame(fileName, lineNumber, className, functionName)));
				}
			});
	}

	public function threads():Array<DebugThread> {
		return Debugger.getThreadInfos();
	}

public function continueThreads(threadNumber:Int, count:Int):Void {
		Debugger.continueThreads(threadNumber, count);
	}

	public function breakNow(wait:Bool):Void {
		Debugger.breakNow(wait);
	}

	public function files():Array<String> {
		return Debugger.getFiles();
	}

	public function filesFullPath():Array<String> {
		return Debugger.getFilesFullPath();
	}

	public function addFileLineBreakpoint(file:String, line:Int):Int {
		return Debugger.addFileLineBreakpoint(file, line);
	}

	public function deleteBreakpoint(number:Int):Void {
		Debugger.deleteBreakpoint(number);
	}
}
#end
