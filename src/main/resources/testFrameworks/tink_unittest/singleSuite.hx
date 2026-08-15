// Entry point the IDE generates for a gutter-started run: one tink_unittest
// suite class from the tests build, compiled with that build's classpaths,
// defines and libraries. The ${TEST_CLASS} token is substituted before the
// compile; this file is a template, never compiled as-is.
class IjSingleRun {
	static function main() {
		var batch = tink.unit.TestBatch.make([new ${TEST_CLASS}()]);
		tink.testrunner.Runner.run(batch).handle(tink.testrunner.Runner.exit);
	}
}
