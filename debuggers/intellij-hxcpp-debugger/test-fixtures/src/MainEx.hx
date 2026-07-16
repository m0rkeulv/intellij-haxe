/**
	Exception-behavior fixture (`fixture-ex.hxml`): the FIXTURE_MODE env var
	selects a scenario so one binary covers all probes. Line numbers are
	load-bearing test constants — extend at the END, never reflow.
**/
class MainEx {
	static function main():Void {
		Sys.println("ex-start:" + Sys.getEnv("FIXTURE_MODE"));
		switch (Sys.getEnv("FIXTURE_MODE")) {
			case "uncaught": uncaughtThrow();
			case "caught": caughtThrow();
			case "caught-null": caughtNullAccess();
			case "null": nullAccess();
			case "spin": spin(); case "getterlock": getterLock(); case "smartstep": SmartStepTarget.run(); case "chain": ChainTarget.loop(); case "threads": Workers.run(); case "typedthrow": TypedThrow.run(); case _: // one line: markers below must not shift
		}
		Sys.println("ex-end");
	}

	static function uncaughtThrow():Void {
		var marker = 42;
		throw "boom-uncaught"; // EX_THROW_LINE = 21
	}

	static function caughtThrow():Void {
		try {
			throw "boom-caught";
		} catch (e:Dynamic) {
			Sys.println("caught:" + e);
		}
	}

	static function caughtNullAccess():Void {
		try {
			var a:Array<Int> = null;
			Sys.println(a.length); // EX_CAUGHT_NULL_LINE = 35
		} catch (e:Dynamic) {
			Sys.println("caught-null:" + e);
		}
	}

	static function nullAccess():Void {
		var a:Array<Int> = null;
		Sys.println(a.length); // EX_NULL_LINE = 43
	}

	// steady pause-able loop for run-control probes; prints a heartbeat so a
	// probe can verify the program actually resumed
	static function spin():Void {
		var beats = 0;
		while (true) {
			beats++;
			if (beats % 100 == 0) {
				Sys.println("beat:" + beats);
			}
			Sys.sleep(0.01);
		}
	}

	// Spins while HOLDING a mutex, with a LockBox local in scope whose property
	// getter acquires that mutex: if the server ever invokes getters while
	// rendering variables, a pause + variables request deadlocks the session
	// (the real-world shape: a paused thread holds a lock a getter needs).
	static function getterLock():Void {
		var box = new LockBox();
		var beats = 0;
		LockBox.mutex.acquire();
		while (true) {
			beats++;
			if (beats % 100 == 0) {
				Sys.println("beat:" + beats);
			}
			Sys.sleep(0.01);
		}
	}
}

class LockBox {
	public static var mutex = new sys.thread.Mutex();

	public var plain:Int = 7;
	// @:isVar = physical backing field, so `danger` IS in getInstanceFields —
	// the shape where Reflect.getProperty invokes the getter (a (get, never)
	// property with no storage never even gets listed on cpp)
	@:isVar public var danger(get, set):Int = 13;

	public function new() {}

	function get_danger():Int {
		mutex.acquire(); // blocks forever while the paused main thread holds it
		mutex.release();
		return this.danger;
	}

	function set_danger(value:Int):Int {
		return this.danger = value;
	}
}

// Smart-step-into scenario: a loop whose body line holds several calls
// (nested + a packaged-class call), so a probe can break on the line and
// enter a CHOSEN callee. Kept in its own class so the runtime class-name
// matching is exercised for both a root class and a packaged one.
class SmartStepTarget {
	public static function run():Void {
		var total = 0;
		while (true) {
			total = combine(one(), fix.PackCounter.bump(1)); // SMART_LINE = 107
			Sys.sleep(0.05);
		}
	}

	static function one():Int {
		return 1; // SMART_ONE_LINE = 113
	}

	static function combine(a:Int, b:Int):Int {
		return a + b; // SMART_COMBINE_LINE = 117
	}
}

// Instance-method chain (`cfg.test1().test2().test3()`) for smart-step
// probes: entry breakpoints must match instance frames too.
class ChainTarget {
	var count = 0;

	public function new() {}

	public static function loop():Void {
		var cfg = new ChainTarget();
		while (true) {
			cfg.test1().test2().test3(); // CHAIN_LINE = 131
			Sys.sleep(0.05);
		}
	}

	public function test1():ChainTarget {
		count++; // CHAIN_T1_LINE = 137
		return this;
	}

	public function test2():ChainTarget {
		count++; // CHAIN_T2_LINE = 142
		return this;
	}

	public function test3():ChainTarget {
		count++; // CHAIN_T3_LINE = 147
		return this;
	}
}

// The multi-threaded shape real apps (lime ThreadPool) have: worker threads
// that never opt into debugging keep RUNNING while main is paused — worker
// heartbeats must keep flowing during a pause, and pause/resume must stay
// healthy with them around.
class Workers {
	public static function run():Void {
		for (i in 0...2) {
			var id = i;
			sys.thread.Thread.create(() -> {
				var n = 0;
				while (true) {
					n++;
					if (n % 25 == 0) {
						Sys.println("worker" + id + ":" + n);
					}
					Sys.sleep(0.01);
				}
			});
		}
		// main heartbeat, pause-able like spin()
		var beats = 0;
		while (true) {
			beats++;
			if (beats % 100 == 0) {
				Sys.println("beat:" + beats);
			}
			Sys.sleep(0.01);
		}
	}
}

// `throw new AppError(...)` INSIDE a try/catch that catches it — the
// "thrown" exception filter (a class-function breakpoint on
// haxe.Exception.new, which every subclass constructor runs through via
// super()) must stop at construction even though the throw is caught.
// See docs/README #15.
class TypedThrow {
	public static function run():Void {
		try {
			throw new AppError("kaboom"); // TYPED_THROW_LINE = 195
		} catch (e:AppError) {
			Sys.println("caught-app:" + e.message);
		}
	}
}

class AppError extends haxe.Exception {}
