// Entry point the IDE generates for a gutter-started run: a munit TestSuite
// containing just the one test class, compiled with the tests build's
// classpaths, defines and libraries. The live client attaches through the
// intellij_munit macro exactly as in a full run. No completionHandler on
// purpose: munit runs on a worker thread on sys targets and its handler
// dispatch calls haxe.Timer.delay there - a thread with no event loop on
// HashLink, so setting a handler crashes the run's completion (verified
// live). Without one the runner's thread handshake ends the process, and
// verdicts ride the reported events. The ${TEST_CLASS} token is substituted
// before the compile; this file is a template, never compiled as-is.
class IjSingleSuite extends massive.munit.TestSuite {
	public function new() {
		super();
		add(${TEST_CLASS});
	}
}

class IjSingleRun {
	static function main() {
		var runner = new massive.munit.TestRunner(new massive.munit.client.PrintClient());
		runner.run([IjSingleSuite]);
	}
}
