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
			case _:
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
}
