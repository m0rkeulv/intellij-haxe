package ijhaxe.debug.values;

import haxe.Int64;

/**
	A value for `ValueWriter.write`: a primitive literal or `null`.
	`VariableMutator.writeValue` produces it from an evaluated expression.

	TODO: nothing produces LString or LPath and ValueWriter rejects both; remove them.
**/
enum ValueLiteral {
	LInt(value:Int64);
	LFloat(value:Float);
	LBool(value:Bool);
	LString(value:String);

	LNull;
	LPath(path:ValuePath);
}
