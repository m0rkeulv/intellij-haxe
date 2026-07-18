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
			// force Server (which self-starts from its static init) into the build.
			// haxe 5 forbids Context.getType from an initialization macro, so defer
			// it to onAfterInitMacros there — the define above is already set, so the
			// deferred load still sees an HXCPP_DEBUGGER-enabled module. onAfterInitMacros
			// only exists on 4.3+, and the direct call is still allowed on 4.1/4.2.
			#if (haxe_ver >= 4.3)
			Context.onAfterInitMacros(() -> Context.getType("intellij.hxcpp.debug.Server"));
			#else
			Context.getType("intellij.hxcpp.debug.Server");
			#end
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
