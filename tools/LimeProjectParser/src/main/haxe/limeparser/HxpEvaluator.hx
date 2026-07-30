package limeparser;

import haxe.io.Path;
import sys.FileSystem;
import sys.io.File;
import sys.io.Process;

/**
	Evaluates a .hxp project script - arbitrary Haxe code extending
	lime.tools.HXProject, so it must RUN, not parse. Mirrors lime's
	HXProject.fromFile mechanics: the script is copied to a temp directory as
	<Name>.hx (class name = capitalized file name), its @:compiler( lines
	become extra compiler args, and the user's haxe executes it with
	-lib lime -lib hxp. The shipped HxpRunner (extracted beside the script)
	replaces lime's serialize/unserialize round trip by printing our JSON
	directly from inside that context.

	Requires haxe plus the lime and hxp haxelibs; returns null (with a message
	on stderr) when evaluation fails.
**/
class HxpEvaluator {
	public static function evaluate(hxpPath:String, target:String, defines:Map<String, String>,
			haxeExecutable:String = "haxe"):Null<String> {
		var absolute = FileSystem.absolutePath(hxpPath);
		var name = className(hxpPath);
		var tempDirectory = createTempDirectory();

		try {
			File.saveContent(Path.join([tempDirectory, name + ".hx"]), File.getContent(absolute));
			File.saveContent(Path.join([tempDirectory, "HxpRunner.hx"]), haxe.Resource.getString("HxpRunner.hx"));

			var args = [name, "-lib", "lime", "-lib", "hxp", "-cp", tempDirectory];
			for (line in compilerLines(absolute)) {
				args = args.concat(line);
			}
			args = args.concat(["--run", "HxpRunner", name, "--target", target]);
			for (define in defines.keys()) {
				var value = defines.get(define);
				args.push("-D");
				args.push(value == "" ? define : define + "=" + value);
			}

			var process = new Process(haxeExecutable, args);
			var stdout = process.stdout.readAll().toString();
			var stderr = process.stderr.readAll().toString();
			var exitCode = process.exitCode();
			process.close();

			if (exitCode != 0) {
				Sys.stderr().writeString("hxp evaluation failed (exit " + exitCode + "):\n" + stderr);
				return null;
			}
			// compiler chatter may precede the runner's output - the JSON is the last line
			var lines = StringTools.trim(stdout).split("\n");
			return StringTools.trim(lines[lines.length - 1]);
		} catch (e:Dynamic) {
			Sys.stderr().writeString("hxp evaluation failed: " + Std.string(e) + "\n");
			return null;
		}
	}

	/** lime's naming rule: the class is the capitalized file name without extension. **/
	public static function className(hxpPath:String):String {
		var name = Path.withoutDirectory(Path.withoutExtension(hxpPath));
		return name.charAt(0).toUpperCase() + name.substr(1);
	}

	/** @:compiler("...") lines in the script carry extra compiler arguments (lime convention). **/
	static function compilerLines(path:String):Array<Array<String>> {
		var result = [];
		var tag = "@:compiler(";
		for (line in File.getContent(path).split("\n")) {
			var trimmed = StringTools.trim(line);
			if (StringTools.startsWith(trimmed, tag)) {
				// strip @:compiler(" and ") - the payload is a quoted argument string
				var payload = trimmed.substring(tag.length + 1, trimmed.length - 2);
				result.push(payload.split(" "));
			}
		}
		return result;
	}

	static function createTempDirectory():String {
		var base = Sys.getEnv("TEMP");
		if (base == null) base = Sys.getEnv("TMPDIR");
		if (base == null) base = "/tmp";
		var directory = Path.join([base, "limeprojectparser-" + Std.string(Std.random(0x7FFFFFFF))]);
		FileSystem.createDirectory(directory);
		return directory;
	}
}
