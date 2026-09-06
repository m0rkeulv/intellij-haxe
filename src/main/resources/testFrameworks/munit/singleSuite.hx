// Entry point the IDE generates for a gutter-started run: a munit TestSuite
// containing just the one test class, compiled with the tests build's
// classpaths, defines and libraries. The live client attaches through the
// intellij_munit macro exactly as in a full run. No completionHandler on
// purpose: munit runs on a worker thread on sys targets and its handler
// dispatch calls haxe.Timer.delay there - a thread with no event loop on
// HashLink, so setting a handler crashes the run's completion (verified
// live). Without one the runner's thread handshake ends the process, and
// verdicts ride the reported events. The ${ADD_SUITES} token (one add() per selected class) is substituted
// before the compile; this file is a template, never compiled as-is.
class IjSingleSuite extends massive.munit.TestSuite {
	public function new() {
		super();
		${ADD_SUITES}
	}
}

class IjSingleRun {
	static function main() {
		// munit's PrintClient on flash is browser-bridged (ExternalInterface)
		// and throws under the IDE's adl host; the summary client is wire-free
		var client:massive.munit.ITestResultClient =
			#if flash new massive.munit.client.SummaryReportClient() #else new massive.munit.client.PrintClient() #end;
		var runner = new massive.munit.TestRunner(client);
		runner.run([IjSingleSuite]);
	}
}
