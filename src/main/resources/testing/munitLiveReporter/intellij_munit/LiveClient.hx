package intellij_munit;

#if !macro
import intellij_haxe_test.TcOutput;
import intellij_haxe_test.TcOutput.announceHostedRunFinished;
import intellij_haxe_test.TcOutput.escape;
import massive.munit.ITestResultClient;
import massive.munit.TestResult;
#if flash
import intellij_haxe_test.FlashSupport;
#end

/**
	Streams one TeamCity service message per test as munit reports it,
	attached into `massive.munit.TestRunner.new` by the IDE's build macro
	(see Macro.hx). munit's clients hear about a test only AFTER it ran, so
	each test emits an adjacent started/finished pair carrying the measured
	duration.

	Implements the base `ITestResultClient` only - the richer interfaces are
	younger than the runner hook, and needing less keeps the injection
	compiling on older munit versions.

	munit's own PrintClient writes progress glyphs without line breaks, so
	every service message starts on a fresh line of its own.

	Event names keep the utest reporter's TeamCity shape (package dots become
	underscores: `unit_crypto.AesTest.testFf1`) so the IDE-side locator serves
	both frameworks unchanged.
**/
class LiveClient implements ITestResultClient {
	public var id(default, null):String = "intellij-live";
	public var completionHandler(get, set):ITestResultClient->Void;
	var handler:ITestResultClient->Void;
	function get_completionHandler():ITestResultClient->Void return handler;
	function set_completionHandler(value:ITestResultClient->Void):ITestResultClient->Void return handler = value;

	final rootSuite:String;
	var rootOpen:Bool = false;
	var openSuite:Null<String> = null;
	var traceBuffer:Array<String> = [];

	public function new(rootSuite:String) {
		this.rootSuite = rootSuite;
		// munit's PrintClient hijacks haxe.Log.trace and prints immediately -
		// BEFORE this client's after-the-fact started/finished pair, so the
		// IDE would attribute the text to the class instead of the test. This
		// client (attached last) re-hijacks: traces buffer here and replay as
		// the reported test's own output; the immediate print is swallowed so
		// the text appears exactly once, on the right node.
		haxe.Log.trace = function(value:Dynamic, ?info:haxe.PosInfos) {
			traceBuffer.push(haxe.Log.formatOutput(value, info));
		};
	}

	public function addPass(result:TestResult):Void {
		reportTest(result, null, null, null);
	}

	public function addFail(result:TestResult):Void {
		var message = result.failure != null ? result.failure.message : "assertion failed";
		var details = result.failure != null ? Std.string(result.failure) : "";
		reportTest(result, message, details, null);
	}

	public function addError(result:TestResult):Void {
		var message = result.error != null ? Std.string(result.error) : "error";
		reportTest(result, message, message, null);
	}

	public function addIgnore(result:TestResult):Void {
		var reason = result.description != null && result.description != "" ? result.description : "ignored";
		reportTest(result, null, null, reason);
	}

	public function reportFinalStatistics(testCount:Int, passCount:Int, failCount:Int, errorCount:Int,
			ignoreCount:Int, time:Float):Dynamic {
		closeSuite();
		if (rootOpen) {
			printLine("##teamcity[testSuiteFinished name='" + escape(rootSuite) + "']");
			rootOpen = false;
		}
		// the runner waits for every client's completion callback before it
		// reports the run finished - not calling it would hang the process
		if (handler != null) handler(this);
		announceHostedRunFinished(failCount + errorCount > 0);
		// nothing on flash ends the process by itself; every service message
		// is already flushed - only the runner's cosmetic summary is cut off
		#if flash
		FlashSupport.exit(0);
		#end
		return null;
	}

	function reportTest(result:TestResult, failureMessage:Null<String>, failureDetails:Null<String>,
			ignoreReason:Null<String>):Void {
		if (!rootOpen && rootSuite != "") {
			printLine("##teamcity[testSuiteStarted name='" + escape(rootSuite) + "']");
			rootOpen = true;
		}
		var suite = suiteName(result.className);
		if (openSuite != suite) {
			closeSuite();
			printLine("##teamcity[testSuiteStarted name='" + escape(suite) + "']");
			openSuite = suite;
		}

		var name = suite + "." + result.name;
		var durationMs = Std.int(result.executionTime * 1000);
		printLine("##teamcity[testStarted name='" + escape(name) + "' captureStandardOutput='true']");
		for (line in traceBuffer) {
			printLine("##teamcity[testStdOut name='" + escape(name) + "' out='" + escape(line + "\n") + "']");
		}
		traceBuffer = [];
		if (ignoreReason != null) {
			printLine("##teamcity[testIgnored name='" + escape(name) + "' message='" + escape(ignoreReason) + "']");
		} else if (failureMessage != null) {
			printLine("##teamcity[testFailed name='" + escape(name) + "' message='" + escape(failureMessage)
				+ "' details='" + escape(failureDetails != null ? failureDetails : "") + "']");
		}
		printLine("##teamcity[testFinished name='" + escape(name) + "' duration='" + durationMs + "']");
	}

	function closeSuite():Void {
		if (openSuite != null) {
			printLine("##teamcity[testSuiteFinished name='" + escape(openSuite) + "']");
			openSuite = null;
		}
	}

	/** The utest reporter's TeamCity name shape: package dots become underscores. **/
	static function suiteName(className:String):String {
		var lastDot = className.lastIndexOf(".");
		if (lastDot < 0) return className;
		var pack = StringTools.replace(className.substr(0, lastDot), ".", "_");
		return pack + "." + className.substr(lastDot + 1);
	}

	static function printLine(line:String):Void {
		// the leading break closes PrintClient's unfinished glyph line (sys
		// only - flash's NATIVE trace and js's console.log are line-oriented
		// already, and the native trace bypasses the haxe.Log hijack above,
		// so the buffered replay cannot re-enter the buffer)
		TcOutput.printLine(line, true);
	}
}
#end
