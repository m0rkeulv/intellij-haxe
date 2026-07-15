package tests;

import intellij.hxcpp.debug.values.Values;

private class Point {
	public var x:Int;
	public var y:Int;

	public function new(x:Int, y:Int) {
		this.x = x;
		this.y = y;
	}

	public function dist():Int {
		return x + y;
	}
}

private enum Shape {
	Circle(r:Int);
	Empty;
}

class ValuesTest {
	public static function run(assert:Assert):Void {
		primitivesAreLeaves(assert);
		arraysExpandToElements(assert);
		objectsExpandToDataFieldsOnly(assert);
		anonymousObjectsExpand(assert);
		enumsExpandToParameters(assert);
	}

	static function primitivesAreLeaves(assert:Assert):Void {
		assert.equals("42", Values.describe(42).value, "int value");
		assert.equals("Int", Values.describe(42).type, "int type");
		assert.isTrue(!Values.describe(42).expandable, "int is a leaf");
		assert.equals('"hi"', Values.describe("hi").value, "string is quoted");
		assert.isTrue(!Values.describe("hi").expandable, "string is a leaf");
		assert.equals("null", Values.describe(null).value, "null");
		assert.isTrue(!Values.describe(true).expandable, "bool is a leaf");
	}

	static function arraysExpandToElements(assert:Assert):Void {
		var arr = [10, 20, 30];
		var d = Values.describe(arr);
		assert.isTrue(d.expandable, "non-empty array expandable");
		assert.equals("Array (3)", d.value, "array summary");
		var kids = Values.children(arr);
		assert.equals(3, kids.length, "three elements");
		assert.equals("[1]", kids[1].name, "indexed name");
		assert.equals(20, kids[1].value, "element value");
		assert.isTrue(!Values.describe([]).expandable, "empty array is a leaf");
	}

	static function objectsExpandToDataFieldsOnly(assert:Assert):Void {
		var p = new Point(3, 4);
		assert.isTrue(Values.describe(p).expandable, "object expandable");
		var kids = Values.children(p);
		var names = [for (k in kids) k.name];
		assert.isTrue(names.indexOf("x") >= 0, "has field x");
		assert.isTrue(names.indexOf("y") >= 0, "has field y");
		assert.isTrue(names.indexOf("dist") < 0, "methods excluded");
	}

	static function anonymousObjectsExpand(assert:Assert):Void {
		var o = {a: 1, b: "two"};
		assert.isTrue(Values.describe(o).expandable, "anon expandable");
		assert.equals(2, Values.children(o).length, "two fields");
	}

	static function enumsExpandToParameters(assert:Assert):Void {
		assert.isTrue(Values.describe(Circle(5)).expandable, "enum with params expandable");
		assert.equals(5, Values.children(Circle(5))[0].value, "enum param value");
		assert.isTrue(!Values.describe(Empty).expandable, "paramless enum is a leaf");
	}
}
