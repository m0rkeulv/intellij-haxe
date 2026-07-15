package intellij.hxcpp.debug;

#if (cpp && HXCPP_DEBUGGER)
import haxe.io.Bytes;
import haxe.io.Eof;
import intellij.hxcpp.debug.DebuggerApi;
import intellij.hxcpp.debug.dap.DapFraming;
import sys.net.Host;
import sys.net.Socket;
import sys.thread.Deque;
import sys.thread.Thread;

/**
	The in-debuggee DAP debug server. Pulled into `cpp -debug` builds by
	Macro.injectServer (see extraParams.hxml); its static init runs BEFORE user
	code, connects OUT to the IDE (which listens on the ephemeral port it put
	into the env vars) and holds the main thread until the IDE has finished
	configuring (breakpoints must exist before user code runs).

	Robustness rule: never harm the host program. No listener, bad config, or
	any wire failure means the app simply runs undebugged; an unconfigured
	build (no env vars, no defines) makes exactly one quick connect attempt.
**/
@:keep
class Server {
	static inline var CONFIGURED_CONNECT_RETRIES = 40; // ~10s while the IDE spins up
	static inline var CONNECT_RETRY_DELAY_S = 0.25;
	static inline var POLL_TIMEOUT_S = 0.05;
	static inline var READ_CHUNK = 4096;

	static function __init__():Void {
		try {
			start();
		} catch (e:Dynamic) {
			// swallow everything: a debug-server failure must never crash the app
		}
	}

	static function start():Void {
		var config = Config.resolve(Sys.getEnv,
			Macro.definedValue("HXCPP_DEBUG_HOST", null),
			Macro.definedValue("HXCPP_DEBUG_PORT", null));
		var socket = connect(config);
		if (socket == null) {
			return; // nobody listening: run undebugged
		}
		var released = new Deque<Bool>();
		Thread.create(() -> run(socket, released));
		// hold user code until the IDE finished configuring (or the wire died)
		released.pop(true);
	}

	static function connect(config:Config.ServerConfig):Null<Socket> {
		var attempts = config.configured ? CONFIGURED_CONNECT_RETRIES : 1;
		for (attempt in 0...attempts) {
			var socket = new Socket();
			try {
				socket.connect(new Host(config.host), config.port);
				return socket;
			} catch (e:Dynamic) {
				try {
					socket.close();
				} catch (e2:Dynamic) {}
				if (attempt < attempts - 1) {
					Sys.sleep(CONNECT_RETRY_DELAY_S);
				}
			}
		}
		return null;
	}

	// The server thread: the ONLY thread doing protocol I/O and the only
	// caller into the Dispatcher. Runtime events arrive queued from the
	// stopping threads and are enriched (stop status) here.
	static function run(socket:Socket, released:Deque<Bool>):Void {
		var api:DebuggerApi = new NativeDebuggerApi();
		api.excludeCurrentThread();
		var events = new Deque<DebugEvent>();
		api.setEventHandler(event -> events.add(event));

		var framing = new DapFraming();
		var dispatcher = new Dispatcher(api, payload -> {
			var frame = DapFraming.encode(payload);
			socket.output.writeFullBytes(frame, 0, frame.length);
		});

		var releasedMain = false;
		function releaseMain():Void {
			if (!releasedMain) {
				releasedMain = true;
				released.add(true);
			}
		}

		try {
			socket.setTimeout(POLL_TIMEOUT_S);
			var buffer = Bytes.alloc(READ_CHUNK);
			while (!dispatcher.shutdownRequested) {
				while (true) {
					var event = events.pop(false);
					if (event == null) {
						break;
					}
					dispatcher.handleDebugEvent(enrich(api, event));
				}
				var read = readChunk(socket, buffer);
				if (read > 0) {
					for (payload in framing.feed(buffer.sub(0, read))) {
						dispatcher.handleRequest(payload);
					}
				}
				if (dispatcher.configurationDone) {
					releaseMain();
				}
			}
		} catch (e:Dynamic) {
			// Eof (IDE went away) or a wire error: stop serving, let the app run
		}
		releaseMain(); // never leave the main thread frozen
		try {
			socket.close();
		} catch (e:Dynamic) {}
	}

	// One bounded read; 0 on poll timeout, throws Eof when the IDE hung up.
	static function readChunk(socket:Socket, buffer:Bytes):Int {
		return try {
			socket.input.readBytes(buffer, 0, READ_CHUNK);
		} catch (e:haxe.io.Error) {
			if (e == Blocked) 0 else throw e;
		}
	}

	// The stop status is unreadable inside the notification callback (it runs
	// on the stopping thread); fill it in here on the server thread.
	static function enrich(api:DebuggerApi, event:DebugEvent):DebugEvent {
		return switch (event) {
			case ThreadStopped(threadNumber, _, frame):
				ThreadStopped(threadNumber, api.threadStatus(threadNumber), frame);
			default:
				event;
		}
	}
}
#end
