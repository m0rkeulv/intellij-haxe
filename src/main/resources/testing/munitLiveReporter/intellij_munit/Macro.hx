package intellij_munit;

#if macro
import haxe.macro.Compiler;
import haxe.macro.Context;
import haxe.macro.Expr;

/**
	Entry point of the IDE's munit test reporting, applied to the test compile
	as `--macro intellij_munit.Macro.init()`. Registers a build macro on
	`massive.munit.TestRunner` that appends an `addResultClient` call to the
	constructor, so per-test TeamCity events stream on stdout alongside
	whatever clients the project's own TestMain installed. munit has no
	TeamCity reporter of its own, so this client IS the IDE's result channel.

	Every failure mode degrades to a plain run instead of breaking the build:
	global metadata on an absent type is inert (a build without munit compiles
	untouched), and the build macro verifies the runner's capability before
	patching - a munit without `addResultClient` gets a warning and unmodified
	fields.
**/
class Macro {
	public static function init():Void {
		Compiler.addGlobalMetadata("massive.munit.TestRunner", "@:build(intellij_munit.Macro.buildRunner())", false, true, false);
	}

	public static function buildRunner():Array<Field> {
		var fields = Context.getBuildFields();
		if (!hasField(fields, "addResultClient")) {
			Context.warning("IDE test reporting needs munit's addResultClient; "
				+ "this munit version has none - results stay console-only", Context.currentPos());
			return fields;
		}
		for (field in fields) {
			if (field.name != "new") continue;
			switch (field.kind) {
				case FFun(fn) if (fn.expr != null):
					var suiteName = Context.definedValue("teamcity_suite_name");
					var rootSuite = suiteName == null ? "" : suiteName;
					fn.expr = macro {
						${fn.expr};
						this.addResultClient(new intellij_munit.LiveClient($v{rootSuite}));
					};
					return fields;
				default:
			}
		}
		Context.warning("IDE test reporting could not patch massive.munit.TestRunner's constructor - results stay console-only",
			Context.currentPos());
		return fields;
	}

	static function hasField(fields:Array<Field>, name:String):Bool {
		for (field in fields) {
			if (field.name == name) return true;
		}
		return false;
	}
}
#end
