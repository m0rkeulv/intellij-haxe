package intellij.hxcpp.debug;

import haxe.macro.Compiler;
import haxe.macro.Context;
import haxe.macro.Expr;

/**
	Build-time entry points of the debug server library.

	`injectServer` is wired through extraParams.hxml, so adding
	`-lib intellij-hxcpp-debug-server` to a `-debug` cpp build is all a user
	does: the server class is pulled into the build (it starts itself from its
	static init) and `HXCPP_DEBUGGER` is defined, which turns on the hxcpp
	runtime's debugger support (checked throws, breakpoint hooks).
**/
class Macro {
	public static function injectServer():Void {
		#if macro
		if (Context.defined("cpp") && Context.defined("debug") && !Context.defined("display")) {
			// define FIRST: Server's whole class is #if HXCPP_DEBUGGER guarded,
			// so pulling the type in before the define finds an empty module
			Compiler.define("HXCPP_DEBUGGER");
			Context.getType("intellij.hxcpp.debug.Server");
		}
		#end
	}

	/**
		The value of compile-time define `key`, or `fallback` when absent — the
		middle link of the env-var -> define -> default configuration chain.
	**/
	macro public static function definedValue(key:String, fallback:Expr):Expr {
		var value = Context.definedValue(key);
		return value == null ? fallback : macro $v{value};
	}
}
