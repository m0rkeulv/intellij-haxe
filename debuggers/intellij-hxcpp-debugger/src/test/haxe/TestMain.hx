/**
	Unit-test entry point, run under the Haxe interpreter (`haxe test.hxml`).
	Register every test class here.
**/
class TestMain {
	static function main():Void {
		var assert = new Assert();
		var tests:Array<{name:String, run:Assert->Void}> = [
			{name: "ConfigTest", run: tests.ConfigTest.run}
		];
		for (test in tests) {
			test.run(assert);
		}
		Sys.println(assert.checks + " checks, " + assert.failures + " failures");
		Sys.exit(assert.failures == 0 ? 0 : 1);
	}
}
