package tests;

import intellij.hxcpp.debug.eval.Evaluator;

class EvaluatorTest {
	public static function run(assert:Assert):Void {
		arithmeticAndComparisons(assert);
		readsFrameLocals(assert);
		fieldAndIndexAccess(assert);
		assignmentWritesBackToTheFrame(assert);
		conditionHoldsIsFailSafe(assert);
	}

	static function make():{eval:Evaluator, api:FakeDebuggerApi} {
		var api = new FakeDebuggerApi();
		return {eval: new Evaluator(api), api: api};
	}

	static function arithmeticAndComparisons(assert:Assert):Void {
		var t = make();
		assert.equals(7, t.eval.evaluate(0, 0, "3 + 4"), "arithmetic");
		assert.equals(true, t.eval.evaluate(0, 0, "2 < 5"), "comparison true");
		assert.equals(false, t.eval.evaluate(0, 0, "10 == 3"), "comparison false");
	}

	static function readsFrameLocals(assert:Assert):Void {
		var t = make();
		t.api.localNames = ["count"];
		t.api.localValues.set("count", 8);
		assert.equals(8, t.eval.evaluate(0, 0, "count"), "reads a local");
		assert.equals(16, t.eval.evaluate(0, 0, "count * 2"), "local in an expression");
		assert.equals(true, t.eval.evaluate(0, 0, "count > 5"), "comparison on a local");
	}

	static function fieldAndIndexAccess(assert:Assert):Void {
		var t = make();
		t.api.localNames = ["p", "nums"];
		t.api.localValues.set("p", {x: 3, y: 4});
		t.api.localValues.set("nums", [10, 20, 30]);
		assert.equals(3, t.eval.evaluate(0, 0, "p.x"), "field access");
		assert.equals(20, t.eval.evaluate(0, 0, "nums[1]"), "array index");
		assert.equals(7, t.eval.evaluate(0, 0, "p.x + p.y"), "compound field expression");
	}

	static function assignmentWritesBackToTheFrame(assert:Assert):Void {
		var t = make();
		t.api.localNames = ["count"];
		t.api.localValues.set("count", 1);
		var result = t.eval.evaluate(0, 0, "count = 99");
		assert.equals(99, result, "assignment returns the stored value");
		assert.equals(1, t.api.setVarCalls.length, "write reached the runtime");
		assert.equals("count", t.api.setVarCalls[0].name, "wrote the right local");
		assert.equals(99, t.api.setVarCalls[0].value, "wrote the evaluated value");
		// `==` is NOT an assignment
		t.eval.evaluate(0, 0, "count == 99");
		assert.equals(1, t.api.setVarCalls.length, "comparison did not write");
	}

	static function conditionHoldsIsFailSafe(assert:Assert):Void {
		var t = make();
		t.api.localNames = ["count"];
		t.api.localValues.set("count", 10);
		assert.isTrue(t.eval.conditionHolds(0, 0, "count > 5"), "true condition holds");
		assert.isTrue(!t.eval.conditionHolds(0, 0, "count > 100"), "false condition does not");
		assert.isTrue(t.eval.conditionHolds(0, 0, "@#$ not valid"), "a broken condition FAILS SAFE (stops)");
		assert.isTrue(t.eval.conditionHolds(0, 0, "count + 1"), "a non-Bool result is treated as stop (fail safe)");
	}
}
