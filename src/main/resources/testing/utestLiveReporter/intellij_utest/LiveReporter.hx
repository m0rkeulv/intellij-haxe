package intellij_utest;

#if !macro
import utest.Assertation;
import utest.Runner;
import utest.TestFixture;
import utest.TestHandler;

/**
	Streams one TeamCity service message per test as it runs, riding the
	runner's public `onTestStart`/`onTestComplete` dispatchers (attached into
	`utest.Runner.new` by the IDE's build macro - see Macro.hx). utest's own
	batch reporter still prints everything again at the end; the IDE
	deduplicates, so a build where the injection did not happen behaves
	exactly as before.

	Event names keep utest's TeamCity shape (package dots become underscores:
	`unit_crypto.AesTest.test_ff1`). A warnings-only test - e.g. "no
	assertions" from a version-gated body that compiled to nothing - reports
	as failed with the warning text as the message, matching the vshaxe
	test-adapter's Warning-to-Failure mapping so both IDEs show one verdict.
**/
class LiveReporter {
	final rootSuite:String;
	var rootOpen:Bool = false;
	var openSuite:Null<String> = null;
	var testStartTime:Float = 0;
	var anyFailed:Bool = false;

	public static function attach(runner:Runner, rootSuite:String):Void {
		#if flash
		FlashSupport.hookTrace();
		#end
		var reporter = new LiveReporter(rootSuite);
		runner.onTestStart.add(reporter.testStart);
		runner.onTestComplete.add(reporter.testComplete);
		runner.onComplete.add(reporter.complete);
	}

	function new(rootSuite:String) {
		this.rootSuite = rootSuite;
	}

	function testStart(handler:TestHandler<TestFixture>):Void {
		if (!rootOpen && rootSuite != "") {
			printLine("##teamcity[testSuiteStarted name='" + escape(rootSuite) + "']");
			rootOpen = true;
		}
		var suite = suiteName(handler.fixture);
		if (openSuite != suite) {
			closeSuite();
			printLine("##teamcity[testSuiteStarted name='" + escape(suite) + "']");
			openSuite = suite;
		}
		printLine("##teamcity[testStarted name='" + escape(testName(handler.fixture)) + "' captureStandardOutput='true']");
		testStartTime = haxe.Timer.stamp();
	}

	function testComplete(handler:TestHandler<TestFixture>):Void {
		var name = testName(handler.fixture);
		var durationMs = Std.int((haxe.Timer.stamp() - testStartTime) * 1000);

		var failure:Null<String> = null;
		var failureDetails = "";
		var warning:Null<String> = null;
		var ignoreReason:Null<String> = null;
		var ignored = false;
		for (assertation in handler.results) {
			switch (assertation) {
				case Assertation.Success(_):
				case Assertation.Failure(msg, pos):
					if (failure == null) failure = msg;
					failureDetails += pos.fileName + ":" + pos.lineNumber + ": " + msg + "\n";
				case Assertation.Error(e, s), Assertation.SetupError(e, s), Assertation.TeardownError(e, s), Assertation.AsyncError(e, s):
					if (failure == null) failure = Std.string(e);
					failureDetails += Std.string(e) + "\n";
				case Assertation.TimeoutError(missedAsyncs, s):
					if (failure == null) failure = "missed async calls: " + missedAsyncs;
					failureDetails += "missed async calls: " + missedAsyncs + "\n";
				case Assertation.Warning(msg):
					if (warning == null) warning = msg;
				case Assertation.Ignore(reason):
					ignored = true;
					ignoreReason = reason;
			}
		}

		if (ignored) {
			var reason = ignoreReason != null && ignoreReason != "" ? ignoreReason : "ignored";
			printLine("##teamcity[testIgnored name='" + escape(name) + "' message='" + escape(reason) + "']");
		} else if (failure != null) {
			anyFailed = true;
			printLine("##teamcity[testFailed name='" + escape(name) + "' message='" + escape(failure)
				+ "' details='" + escape(failureDetails) + "']");
		} else if (warning != null) {
			anyFailed = true;
			printLine("##teamcity[testFailed name='" + escape(name) + "' message='" + escape(warning) + "']");
		}
		printLine("##teamcity[testFinished name='" + escape(name) + "' duration='" + durationMs + "']");
	}

	function complete(runner:Runner):Void {
		closeSuite();
		if (rootOpen) {
			printLine("##teamcity[testSuiteFinished name='" + escape(rootSuite) + "']");
			rootOpen = false;
		}
		announceHostedRunFinished(anyFailed);
		// nothing on flash ends the process by itself; every line above is
		// already flushed (adl forwards traces synchronously)
		#if flash
		FlashSupport.exit(0);
		#end
	}

	// A BROWSER-hosted page has no process exit; the IDE ends the run when
	// this line arrives (node/sys runs exit by themselves, flash through adl).
	static function announceHostedRunFinished(failed:Bool):Void {
		#if js
		var proc:Dynamic = js.Syntax.code("typeof process !== 'undefined' ? process : null");
		if (proc == null) printLine("##intellij-haxe[testRunFinished exit='" + (failed ? 1 : 0) + "']");
		#end
	}

	function closeSuite():Void {
		if (openSuite != null) {
			printLine("##teamcity[testSuiteFinished name='" + escape(openSuite) + "']");
			openSuite = null;
		}
	}

	/** utest's TeamCity name shape: package dots become underscores. **/
	function suiteName(fixture:TestFixture):String {
		var className = Type.getClassName(Type.getClass(fixture.target));
		var lastDot = className.lastIndexOf(".");
		if (lastDot < 0) return className;
		var pack = StringTools.replace(className.substr(0, lastDot), ".", "_");
		return pack + "." + className.substr(lastDot + 1);
	}

	function testName(fixture:TestFixture):String {
		return suiteName(fixture) + "." + fixture.method;
	}

	static function printLine(line:String):Void {
		#if sys
		Sys.println(line);
		#elseif flash
		flash.Lib.trace(line);
		#elseif js
		// console.log reaches node's stdout and the browser console alike;
		// the trace fallback would prefix every line with its own position
		untyped console.log(line);
		#else
		trace(line);
		#end
	}

	// TeamCity value escaping: https://www.jetbrains.com/help/teamcity/service-messages.html
	static function escape(value:String):String {
		value = StringTools.replace(value, "|", "||");
		value = StringTools.replace(value, "'", "|'");
		value = StringTools.replace(value, "\n", "|n");
		value = StringTools.replace(value, "\r", "|r");
		value = StringTools.replace(value, "[", "|[");
		value = StringTools.replace(value, "]", "|]");
		return value;
	}
}
#end
