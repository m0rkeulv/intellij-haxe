/**
	Debuggee for the eval live tests, run with `haxe --interp`.

	WARNING: line numbers are load-bearing test constants
	(EvalLiveTest.BREAK_LINE) — extend at the END, never reflow.
**/
class EvalMain {
	static function main() {
		var greeting = "hello";
		var count = greeting.length + 2; // EvalLiveTest.BREAK_LINE = 10
		Sys.println("eval-fixture:" + greeting + ":" + count);
	}
}
