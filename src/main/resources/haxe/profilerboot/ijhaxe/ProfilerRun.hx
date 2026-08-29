package ijhaxe;

#if cpp
/**
	Runtime side of the injected profiling bootstrap: start plus an
	idempotent stop — hxcpp's stop crashes on a second call, and both the
	main-return and the System.exit instrumentation may reach it. When the
	build carries HXCPP_TELEMETRY and the IDE handed us an endpoint, the
	telemetry collector streams alongside the text-report profiler.
**/
class ProfilerRun {
	static var stopped = false;

	public static function start(dumpFile:String):Void {
		cpp.vm.Profiler.start(dumpFile);
		#if (HXCPP_TELEMETRY && !HXCPP_TRACY)
		ijhaxe.TelemetryRun.tryStart();
		#end
	}

	public static function stopOnce():Void {
		if (stopped) return;
		stopped = true;
		#if (HXCPP_TELEMETRY && !HXCPP_TRACY)
		ijhaxe.TelemetryRun.stop();
		#end
		cpp.vm.Profiler.stop();
	}
}
#end
