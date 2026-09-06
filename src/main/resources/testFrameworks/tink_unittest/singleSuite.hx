// Entry point the IDE generates for a gutter-started run: one tink_unittest
// suite class from the tests build, compiled with that build's classpaths,
// defines and libraries. The ${NEW_SUITES} token (one `new Suite()` per selected class) is substituted before the
// compile; this file is a template, never compiled as-is.
class IjSingleRun {
	static function main() {
		var batch = tink.unit.TestBatch.make([${NEW_SUITES}]);
		tink.testrunner.Runner.run(batch).handle(exitHost);
	}

	// tink's own Runner.exit throws "not supported" on plain js (its exit
	// helper only knows the travix/nodejs/phantom hosts); a node-hosted
	// plain-js build reaches the runtime through the process global instead
	static function exitHost(result:tink.testrunner.Result.BatchResult):Void {
		var code:Int = result.summary().failures.length;
		#if (sys || nodejs)
		Sys.exit(code);
		#elseif js
		var proc:Dynamic = js.Syntax.code("typeof process !== 'undefined' ? process : null");
		if (proc != null) proc.exit(code);
		#else
		tink.testrunner.Runner.exit(result);
		#end
	}
}
