package intellij.hxcpp.debug.values;

/**
	Renders and expands live debuggee values by ordinary reflection — the whole
	point of an IN-PROCESS server: a value is a real Haxe object, so
	`Type.typeof`/`Reflect` describe and walk it with no memory decoding. Pure
	and target-neutral, so it runs under the interpreter against plain values in
	the unit tests exactly as it does over real cpp values.

	SAFETY RULE: never run user code implicitly. Rendering happens on the
	server thread while the debuggee's threads are PAUSED — a property getter
	or toString that takes a lock held by a paused thread wedges the whole
	session (observed live). So fields are read RAW (`Reflect.field`, which
	never invokes a getter) and objects are labeled by class name, never
	stringified. Calling a getter is what `evaluate` is for — an explicit,
	user-initiated risk.
**/
class Values {
	/** Display string + type name + whether the value has expandable children. */
	public static function describe(value:Dynamic):{value:String, type:String, expandable:Bool} {
		return switch (Type.typeof(value)) {
			case TNull: {value: "null", type: "Unknown", expandable: false};
			case TInt: {value: Std.string(value), type: "Int", expandable: false};
			case TFloat: {value: Std.string(value), type: "Float", expandable: false};
			case TBool: {value: Std.string(value), type: "Bool", expandable: false};
			case TFunction: {value: "<function>", type: "Function", expandable: false};
			case TClass(c) if (c == String): {value: '"' + Std.string(value) + '"', type: "String", expandable: false};
			case TClass(c) if (c == Array):
				var arr:Array<Dynamic> = value;
				{value: "Array (" + arr.length + ")", type: "Array", expandable: arr.length > 0};
			case TClass(c):
				var name = Type.getClassName(c);
				{value: name, type: name, expandable: dataFields(value, c).length > 0};
			case TObject: {value: "{ }", type: "Anonymous", expandable: Reflect.fields(value).length > 0};
			case TEnum(e):
				var params = Type.enumParameters(value);
				var ctor = Type.enumConstructor(value);
				{value: params.length > 0 ? ctor + "(…)" : ctor, type: Type.getEnumName(e), expandable: params.length > 0};
			case TUnknown: {value: Std.string(value), type: "Unknown", expandable: false};
		};
	}

	/** The named/indexed children of an expandable value (empty for a leaf). */
	public static function children(value:Dynamic):Array<{name:String, value:Dynamic}> {
		return switch (Type.typeof(value)) {
			case TClass(c) if (c == String): [];
			case TClass(c) if (c == Array):
				var arr:Array<Dynamic> = value;
				[for (i in 0...arr.length) {name: "[" + i + "]", value: arr[i]}];
			case TClass(c):
				[for (f in dataFields(value, c)) {name: f, value: rawField(value, f)}];
			case TObject:
				[for (f in Reflect.fields(value)) {name: f, value: Reflect.field(value, f)}];
			case TEnum(_):
				var params = Type.enumParameters(value);
				[for (i in 0...params.length) {name: "[" + i + "]", value: params[i]}];
			default: [];
		};
	}

	// Instance fields that hold data (not methods) — the ones a user inspects.
	// Raw reads only: getProperty would run every getter of every object in
	// scope just to LIST locals (see the class doc's safety rule).
	static function dataFields(value:Dynamic, c:Class<Dynamic>):Array<String> {
		return [for (f in Type.getInstanceFields(c)) if (!Reflect.isFunction(rawField(value, f))) f];
	}

	// Reflect.field never invokes a getter; a computed (non-physical) property
	// simply doesn't appear, which is the safe rendering of it.
	static function rawField(value:Dynamic, name:String):Dynamic {
		return try Reflect.field(value, name) catch (e:Dynamic) null;
	}
}
