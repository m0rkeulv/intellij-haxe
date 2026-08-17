package intellij_buddy;

#if !macro
import buddy.BuddySuite.Spec;
import buddy.BuddySuite.SpecStatus;
import buddy.BuddySuite.Suite;
import haxe.CallStack;
import intellij_haxe_test.TcOutput.announceHostedRunFinished;
import intellij_haxe_test.TcOutput.escape;
import intellij_haxe_test.TcOutput.printLine;
import promhx.Deferred;
import promhx.Promise;

/**
	Buddy reporter emitting TeamCity service messages, selected into the build
	with buddy's own `-D reporter=intellij_buddy.TcReporter` override — no
	macro patching involved. Events are emitted as one BATCH from `done`:
	buddy's per-spec `progress` callback carries no suite context, and only
	the finished tree has the describe-nesting, per-spec durations and the
	captured traces (which attribute to their spec via testStdOut).

	Spec descriptions are prose, not identifiers — names stay as written and
	the IDE simply has no source navigation for them (closure-built specs have
	no method PSI to map to; the VSCode adapter shares that ceiling).
**/
class TcReporter implements buddy.reporting.Reporter {
	public function new() {}

	public function start():Promise<Bool> {
		return resolveImmediately(true);
	}

	public function progress(spec:Spec):Promise<Spec> {
		return resolveImmediately(spec);
	}

	public function done(suites:Iterable<Suite>, status:Bool):Promise<Iterable<Suite>> {
		var rootSuite = SuiteName.defined();
		if (rootSuite != "") printLine("##teamcity[testSuiteStarted name='" + escape(rootSuite) + "']");
		for (suite in suites) reportSuite(suite);
		if (rootSuite != "") printLine("##teamcity[testSuiteFinished name='" + escape(rootSuite) + "']");
		announceHostedRunFinished(!status);
		return resolveImmediately(suites);
	}

	function reportSuite(suite:Suite):Void {
		var named = suite.description.length > 0;
		if (named) printLine("##teamcity[testSuiteStarted name='" + escape(suite.description) + "']");

		// a crashed describe body (its error field) never ran its specs -
		// surface it as one failed test so the run cannot look green
		if (suite.error != null) {
			var name = suite.description + " (suite error)";
			printLine("##teamcity[testStarted name='" + escape(name) + "']");
			printLine("##teamcity[testFailed name='" + escape(name) + "' message='" + escape(Std.string(suite.error))
				+ "' details='" + escape(stackText(suite.stack)) + "']");
			printLine("##teamcity[testFinished name='" + escape(name) + "']");
		} else {
			for (step in suite.steps) switch step {
				case TSpec(spec): reportSpec(spec);
				case TSuite(inner): reportSuite(inner);
			}
		}

		if (named) printLine("##teamcity[testSuiteFinished name='" + escape(suite.description) + "']");
	}

	function reportSpec(spec:Spec):Void {
		var name = spec.description;
		var durationMs = Std.int(spec.time * 1000);
		// the it() call site's file plus the description literal - buddy's
		// Spec drops the call site's line, so the IDE locates the literal
		var location = spec.fileName != "" ? " locationHint='haxe:buddy://"
			+ escape(spec.fileName + "::" + spec.description) + "'" : "";
		printLine("##teamcity[testStarted name='" + escape(name) + "'" + location + "]");
		for (trace in spec.traces) {
			printLine("##teamcity[testStdOut name='" + escape(name) + "' out='" + escape(trace + "\n") + "']");
		}
		switch spec.status {
			case Failed:
				var message = spec.failures.length > 0 ? Std.string(spec.failures[0].error) : "failed";
				var details = "";
				for (failure in spec.failures) {
					details += Std.string(failure.error) + "\n" + stackText(failure.stack);
				}
				printLine("##teamcity[testFailed name='" + escape(name) + "' message='" + escape(message)
					+ "' details='" + escape(details) + "']");
			case Pending:
				printLine("##teamcity[testIgnored name='" + escape(name) + "' message='pending']");
			case Unknown:
				printLine("##teamcity[testIgnored name='" + escape(name) + "' message='not run']");
			case Passed:
		}
		printLine("##teamcity[testFinished name='" + escape(name) + "' duration='" + durationMs + "']");
	}

	static function stackText(stack:Array<StackItem>):String {
		if (stack == null) return "";
		var lines = "";
		for (item in stack) switch item {
			case FilePos(_, file, line) if (line > 0 && file.indexOf("buddy/") != 0):
				lines += file + ":" + line + "\n";
			case _:
		}
		return lines;
	}

	function resolveImmediately<T>(value:T):Promise<T> {
		var deferred = new Deferred<T>();
		var promise = deferred.promise();
		deferred.resolve(value);
		return promise;
	}
}
#end
