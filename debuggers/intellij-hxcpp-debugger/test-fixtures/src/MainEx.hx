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
			case "spin": spin(); case "getterlock": getterLock(); case "smartstep": SmartStepTarget.run(); case _: // one line: markers below must not shift
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
			Sys.println(a.length); // EX_CAUGHT_NULL_LINE = 34
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
