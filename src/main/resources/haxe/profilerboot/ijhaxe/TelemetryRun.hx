package ijhaxe;

// under HXCPP_TRACY the pollable telemetry API is replaced by Tracy stubs
// that THROW - a tracy build profiles through Tracy's own UI instead
#if (cpp && HXCPP_TELEMETRY && !HXCPP_TRACY)
import haxe.io.Bytes;
import haxe.io.BytesOutput;

/**
	Streams hxcpp telemetry frames to the IDE as an HXTS session over the
	socket named by the IJ_HAXE_TELEMETRY env var (host:port). Without the
	var, or on any failure, telemetry stays off — the application is never
	disturbed. Stash/dump must run on the instrumented (main) thread, so a
	haxe.Timer drives them there (lime pumps the main event loop); a writer
	thread owns the socket so sends never stall a frame. stop() flushes the
	final frame and briefly waits for the writer to drain — the process
	usually dies in Sys.exit right after.
**/
class TelemetryRun {
	static var threadNum = -1;
	static var socket:sys.net.Socket;
	static var queue:sys.thread.Deque<Bytes>;
	static var drained:sys.thread.Lock;
	static var timer:haxe.Timer;
	static var stopped = false;

	public static function tryStart():Void {
		var endpoint = Sys.getEnv("IJ_HAXE_TELEMETRY");
		if (endpoint == null) return;
		var colon = endpoint.lastIndexOf(":");
		if (colon <= 0) return;
		try {
			var port = Std.parseInt(endpoint.substr(colon + 1));
			if (port == null) return;
			socket = new sys.net.Socket();
			socket.connect(new sys.net.Host(endpoint.substr(0, colon)), port);
			socket.setFastSend(true);
		} catch (e:Dynamic) {
			socket = null;
			return;
		}

		try {
			threadNum = CppTelemetry.start();
			queue = new sys.thread.Deque();
			drained = new sys.thread.Lock();
			sys.thread.Thread.create(writerLoop);
			queue.add(header());
			// the runtime pre-stashed a blank frame at start; the first dump discards it
			CppTelemetry.stash();
			timer = new haxe.Timer(16);
			timer.run = tick;
		} catch (e:Dynamic) {
			// a runtime whose telemetry entry points reject (future stubs) must not crash the app
			try socket.close() catch (closeError:Dynamic) {}
			socket = null;
		}
	}

	/** Flushes the final frame and closes the stream; safe to call more than once. */
	public static function stop():Void {
		if (socket == null || stopped) return;
		stopped = true;
		if (timer != null) timer.stop();
		try {
			CppTelemetry.stash();
			shipFrame();
		} catch (e:Dynamic) {}
		queue.add(Bytes.alloc(0)); // sentinel: writer drains, closes, releases
		drained.wait(0.5);
	}

	static function tick():Void {
		if (stopped) return;
		try {
			CppTelemetry.stash();
			shipFrame();
		} catch (e:Dynamic) {
			stopped = true;
		}
	}

	static function shipFrame():Void {
		var gcTimes = new Array<Int>();
		var names = new Array<String>();
		var samples = new Array<Int>();
		if (!CppTelemetry.dumpInto(threadNum, gcTimes, names, samples)) return;

		var payload = output();
		payload.writeDouble(haxe.Timer.stamp());
		payload.writeInt32(gcTimes[0]);
		payload.writeInt32(gcTimes[1]);
		payload.writeInt32(CppTelemetry.usedBytes());
		payload.writeInt32(CppTelemetry.reservedBytes());
		payload.writeInt32(names.length);
		for (name in names)
			writeName(payload, name);
		payload.writeInt32(samples.length);
		for (value in samples)
			payload.writeInt32(value);

		var record = output();
		record.writeByte(1); // FRAME
		var bytes = payload.getBytes();
		record.writeInt32(bytes.length);
		record.write(bytes);
		queue.add(record.getBytes());
	}

	static function header():Bytes {
		var out = output();
		out.writeString("HXTS");
		out.writeUInt16(1);
		out.writeInt32(1000); // the hxcpp sampler's fixed 1 ms tick
		out.writeDouble(haxe.Timer.stamp());
		writeName(out, "hxcpp");
		return out.getBytes();
	}

	static function writeName(out:BytesOutput, name:String):Void {
		var utf8 = Bytes.ofString(name);
		out.writeUInt16(utf8.length);
		out.write(utf8);
	}

	static function output():BytesOutput {
		var out = new BytesOutput();
		out.bigEndian = false;
		return out;
	}

	static function writerLoop():Void {
		while (true) {
			var chunk = queue.pop(true);
			if (chunk == null || chunk.length == 0) break;
			try {
				socket.output.write(chunk);
			} catch (e:Dynamic) {
				stopped = true;
				break;
			}
		}
		try {
			socket.close();
		} catch (e:Dynamic) {}
		drained.release();
	}
}
#end
