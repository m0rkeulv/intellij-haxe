/**
	Debuggee for the eval live tests, run with `haxe --interp`.

	WARNING: line numbers are load-bearing test constants
	(EvalLiveTest / EvalDebugAdapterLiveTest) — extend at the END, never reflow.
**/
class EvalMain {
	static function main() {
		var greeting = "hello";
		var count = greeting.length + 2; // BREAK_LINE = 10
		var nested = outer(inner(3)); // NESTED_CALL_LINE = 11
		Sys.println("eval-fixture:" + greeting + ":" + count + ":" + nested);
	}

	static function inner(x:Int):Int {
		return x * 2; // INNER_LINE = 16 (first executable line eval stops on)
	}

	static function outer(x:Int):Int {
		return x + 1;
	}
}
