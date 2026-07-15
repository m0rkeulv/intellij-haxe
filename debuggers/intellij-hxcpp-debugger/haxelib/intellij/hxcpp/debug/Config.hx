package intellij.hxcpp.debug;

typedef ServerConfig = {
	var host:String;
	var port:Int;
}

/**
	Connection-settings resolution: environment variables first (the IDE sets
	`HXCPP_DEBUG_HOST`/`HXCPP_DEBUG_PORT` on the spawned process, with an
	ephemeral port per session — no rebuilds, no port collisions), then the
	compile-time defines (compatible with the vshaxe server's), then the
	defaults. Pure: the env reader is injected so unit tests run under the
	interpreter.
**/
class Config {
	public static inline var DEFAULT_HOST = "127.0.0.1";
	public static inline var DEFAULT_PORT = 6972;

	public static function resolve(env:String->Null<String>, ?defineHost:String, ?definePort:String):ServerConfig {
		var host = nonEmpty(env("HXCPP_DEBUG_HOST"));
		if (host == null) {
			host = nonEmpty(defineHost);
		}
		var port = parsePort(env("HXCPP_DEBUG_PORT"));
		if (port == null) {
			port = parsePort(definePort);
		}
		return {
			host: host != null ? host : DEFAULT_HOST,
			port: port != null ? port : DEFAULT_PORT
		};
	}

	static function nonEmpty(value:Null<String>):Null<String> {
		if (value == null) {
			return null;
		}
		var trimmed = StringTools.trim(value);
		return trimmed == "" ? null : trimmed;
	}

	// null unless a valid TCP port (1..65535): a typo falls through the chain
	// instead of aiming the server at port 0 or garbage
	static function parsePort(value:Null<String>):Null<Int> {
		if (value == null) {
			return null;
		}
		var parsed = Std.parseInt(StringTools.trim(value));
		return parsed != null && parsed > 0 && parsed <= 65535 ? parsed : null;
	}
}
