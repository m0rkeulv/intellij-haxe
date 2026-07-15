package intellij.hxcpp.debug.eval;

import hscript.Interp;
import hscript.Parser;
import intellij.hxcpp.debug.DebuggerApi;

/**
	Evaluates watch/hover/condition expressions against a stopped frame.

	In-process again pays off: hscript parses the expression and evaluates it by
	ordinary reflection, so operators, comparisons, field access, indexing and
	method calls all work with no interpreter of our own. The frame's locals
	(and `this`) are bridged into hscript's variable scope; a bare `ident = expr`
	assignment is routed back to the runtime so it persists in the debuggee.

	Pure Haxe, so it runs under the interpreter in the unit tests.
**/
class Evaluator {
	final debugger:DebuggerApi;
	final parser = new Parser();

	public function new(debugger:DebuggerApi) {
		this.debugger = debugger;
	}

	/**
		Evaluates `expression` in `frame` of `thread` and returns the resulting
		value. A top-level `name = value` writes back to the frame (and returns
		the stored value). Throws a String message on a parse/eval error.
	**/
	public function evaluate(thread:Int, frame:Int, expression:String):Dynamic {
		var trimmed = StringTools.trim(expression);
		var assignment = topLevelAssignment(trimmed);

		var interp = new Interp();
		for (name in debugger.stackVariables(thread, frame)) {
			interp.variables.set(name, debugger.stackVariableValue(thread, frame, name));
		}

		if (assignment != null) {
			var value = run(interp, assignment.rhs);
			// persist to the debuggee frame, not just hscript's scratch scope
			return debugger.setStackVariableValue(thread, frame, assignment.name, value);
		}
		return run(interp, trimmed);
	}

	function run(interp:Interp, source:String):Dynamic {
		var program = try {
			parser.parseString(source);
		} catch (e:Dynamic) {
			throw "Cannot parse expression: " + Std.string(e);
		}
		return try {
			interp.execute(program);
		} catch (e:Dynamic) {
			throw Std.string(e);
		}
	}

	/**
		Evaluates `condition` as a Bool for a conditional breakpoint. FAIL SAFE:
		any error (bad expression, non-Bool, missing local) returns true, so a
		broken condition stops rather than silently swallowing the breakpoint —
		the caller surfaces the reason.
	**/
	public function conditionHolds(thread:Int, frame:Int, condition:String):Bool {
		return try {
			var result = evaluate(thread, frame, condition);
			// only a real Bool decides; anything else fails safe (stops)
			Std.isOfType(result, Bool) ? (result : Bool) : true;
		} catch (e:Dynamic) {
			true;
		}
	}

	// `name = rhs` where `name` is a bare identifier (not `==`, `<=`, etc.).
	// Returns null when the expression is not such an assignment.
	static function topLevelAssignment(source:String):Null<{name:String, rhs:String}> {
		var eq = findTopLevelAssign(source);
		if (eq < 0) {
			return null;
		}
		var name = StringTools.trim(source.substr(0, eq));
		if (!isIdentifier(name)) {
			return null;
		}
		return {name: name, rhs: StringTools.trim(source.substr(eq + 1))};
	}

	// Index of a top-level `=` that is a plain assignment (not ==, !=, <=, >=),
	// ignoring anything inside brackets/parens/strings; -1 if none.
	static function findTopLevelAssign(source:String):Int {
		var depth = 0;
		var inString = false;
		var quote = 0;
		var i = 0;
		while (i < source.length) {
			var c = source.charCodeAt(i);
			if (inString) {
				if (c == quote) inString = false;
			} else if (c == "'".code || c == '"'.code) {
				inString = true;
				quote = c;
			} else if (c == "(".code || c == "[".code) {
				depth++;
			} else if (c == ")".code || c == "]".code) {
				depth--;
			} else if (depth == 0 && c == "=".code) {
				var prev = i > 0 ? source.charCodeAt(i - 1) : 0;
				var next = i + 1 < source.length ? source.charCodeAt(i + 1) : 0;
				var comparison = next == "=".code || prev == "=".code
					|| prev == "!".code || prev == "<".code || prev == ">".code;
				if (!comparison) {
					return i;
				}
			}
			i++;
		}
		return -1;
	}

	static function isIdentifier(s:String):Bool {
		if (s.length == 0) {
			return false;
		}
		for (i in 0...s.length) {
			var c = s.charCodeAt(i);
			var ok = (c >= "a".code && c <= "z".code) || (c >= "A".code && c <= "Z".code)
				|| c == "_".code || (i > 0 && c >= "0".code && c <= "9".code);
			if (!ok) {
				return false;
			}
		}
		return true;
	}
}
