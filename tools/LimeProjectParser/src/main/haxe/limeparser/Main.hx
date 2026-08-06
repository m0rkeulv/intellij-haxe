package limeparser;

import haxe.Json;
import haxe.io.Path;
import sys.FileSystem;
import sys.io.File;

/**
	CLI entry: evaluates a lime/openfl project file and prints the build
	configuration as JSON. project.xml is evaluated natively; project.hxp is a
	Haxe script and runs via the user's haxe with -lib lime -lib hxp (see
	HxpEvaluator).

	Usage: LimeProjectParser <project.xml|project.hxp> [--target <id>] [--command <cmd>] [-D name[=value]]...

	The caller supplies the seed defines (target, platform, tool versions) via
	-D; the tool itself stays ignorant of how they are derived. Relative
	<include> paths resolve against the project file's directory.
**/
class Main {
	static function main():Void {
		var args = Sys.args();
		if (args.length == 0) {
			Sys.stderr().writeString("usage: LimeProjectParser <project.xml|project.hxp> [--target <id>] [--command <cmd>] [-D name[=value]]...\n");
			Sys.exit(2);
		}

		var projectFile:String = null;
		var command = "display";
		var target = "windows";
		var haxeExecutable = "haxe";
		var haxelibExecutable = "haxelib";
		var seedDefines:Map<String, String> = [];

		var i = 0;
		while (i < args.length) {
			var arg = args[i];
			if (arg == "--command" && i + 1 < args.length) {
				command = args[++i];
			} else if (arg == "--target" && i + 1 < args.length) {
				target = args[++i];
			} else if (arg == "--haxe" && i + 1 < args.length) {
				haxeExecutable = args[++i];
			} else if (arg == "--haxelib" && i + 1 < args.length) {
				haxelibExecutable = args[++i];
			} else if (arg == "-D" && i + 1 < args.length) {
				var pair = args[++i].split("=");
				seedDefines.set(pair[0], pair.length > 1 ? pair.slice(1).join("=") : "");
			} else if (projectFile == null) {
				projectFile = arg;
			}
			i++;
		}

		if (projectFile == null || !FileSystem.exists(projectFile)) {
			Sys.stderr().writeString("project file not found: " + projectFile + "\n");
			Sys.exit(2);
		}

		if (Path.extension(projectFile).toLowerCase() == "hxp") {
			var json = HxpEvaluator.evaluate(projectFile, target, seedDefines, haxeExecutable);
			if (json == null) {
				Sys.exit(1);
			}
			Sys.println(json);
			return;
		}

		var projectDirectory = Path.directory(FileSystem.absolutePath(projectFile));
		var evaluator = new ProjectXmlEvaluator(seedDefines, command, Sys.environment(), path -> {
			var resolved = Path.isAbsolute(path) ? path : Path.join([projectDirectory, path]);
			if (FileSystem.exists(resolved) && FileSystem.isDirectory(resolved)) {
				resolved = Path.join([resolved, "include.xml"]);
			}
			return FileSystem.exists(resolved) ? File.getContent(resolved) : null;
		}, HaxelibLookup.resolver(haxelibExecutable));
		evaluator.parse(File.getContent(projectFile));

		Sys.println(Json.stringify({
			defines: mapToObject(evaluator.defines),
			haxedefs: mapToObject(evaluator.haxedefs),
			haxelibs: evaluator.haxelibs,
			sources: evaluator.sources,
			app: {path: evaluator.appPath, file: evaluator.appFile},
		}));
	}

	static function mapToObject(map:Map<String, String>):Dynamic {
		var object = {};
		for (name => value in map) {
			Reflect.setField(object, name, value);
		}
		return object;
	}
}
