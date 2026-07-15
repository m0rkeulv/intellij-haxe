package intellij.hxcpp.debug;

/**
	The in-debuggee DAP debug server. M0 stub: pulled into `cpp -debug` builds
	by Macro.injectServer (see extraParams.hxml); the wire core (debugger
	thread, connect-out socket, DAP framing) lands in M1. The static init only
	resolves the connection config so the injection path and the
	env -> define -> default chain are exercised by a real build.
**/
@:keep
class Server {
	#if (cpp && HXCPP_DEBUGGER)
	static function __init__():Void {
		var config = Config.resolve(Sys.getEnv,
			Macro.definedValue("HXCPP_DEBUG_HOST", null),
			Macro.definedValue("HXCPP_DEBUG_PORT", null));
		// M1: start the debugger thread and connect out to config.host:config.port.
		// M0 keeps the debuggee untouched (never crash/slow the host program).
		if (config == null) {} // silence "unused" until M1 consumes it
	}
	#end
}
