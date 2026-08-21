// Entry point the IDE generates for a gutter-started single-test run: the
// suite's one case whose method matches is flipped to include=true, which
// puts the runner into include mode (everything else is skipped). The
// ${TEST_CLASS}/${TEST_METHOD} tokens are substituted before the compile;
// this file is a template, never compiled as-is.
class IjSingleRun {
	static function main() {
		var batch = tink.unit.TestBatch.make([new ${TEST_CLASS}()]);
		for (suite in batch.suites)
			for (caze in suite.cases)
				if (caze.info.pos.methodName == "${TEST_METHOD}")
					caze.include = true;
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
