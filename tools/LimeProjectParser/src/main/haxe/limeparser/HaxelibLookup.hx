package limeparser;

import haxe.io.Path;
import limeparser.ProjectXmlEvaluator.ResolvedHaxelib;
import sys.FileSystem;
import sys.io.File;
import sys.io.Process;

/**
	Resolves a haxelib and its transitive dependency chain by parsing
	`haxelib path <name[:version]>` output. Per library, in dependency order,
	haxelib prints: -L ndll lines, the library's extraParams.hxml content
	INLINE, one or more bare classpath lines, then a `-D name=version` marker
	closing that library. A -D seen before any classpath therefore belongs to
	extraParams, not the marker. Each library root is probed for an include.xml.
**/
class HaxelibLookup {
	public static function resolver(haxelibExecutable:String):(String, String) -> Null<Array<ResolvedHaxelib>> {
		var cache:Map<String, Null<Array<ResolvedHaxelib>>> = [];
		return (name, version) -> {
			var spec = version == "" ? name : name + ":" + version;
			if (!cache.exists(spec)) {
				cache.set(spec, resolve(haxelibExecutable, spec));
			}
			return cache.get(spec);
		};
	}

	static function resolve(haxelibExecutable:String, spec:String):Null<Array<ResolvedHaxelib>> {
		var output:String;
		try {
			var process = new Process(haxelibExecutable, ["path", spec]);
			output = process.stdout.readAll().toString();
			process.stderr.readAll();
			var exitCode = process.exitCode();
			process.close();
			if (exitCode != 0) return null;
		} catch (e:Dynamic) {
			return null;
		}
		return parseOutput(output);
	}

	/** Pure parse of `haxelib path` output, separated from process spawning for testability. **/
	public static function parseOutput(output:String):Array<ResolvedHaxelib> {
		var libraries:Array<ResolvedHaxelib> = [];
		var pendingClasspaths:Array<String> = [];
		var pendingExtraDefines:Array<String> = [];
		var pendingExtraArgs:Array<String> = [];
		for (rawLine in output.split("\n")) {
			var line = StringTools.trim(rawLine);
			if (line == "") continue;

			if (StringTools.startsWith(line, "-D ")) {
				if (pendingClasspaths.length > 0) {
					// the version marker (-D name=1.2.3) closes the library
					var pair = line.substr(3).split("=");
					libraries.push(makeLibrary(pair[0], pair.length > 1 ? pair[1] : "",
						pendingClasspaths, pendingExtraDefines, pendingExtraArgs));
					pendingClasspaths = [];
					pendingExtraDefines = [];
					pendingExtraArgs = [];
				} else {
					// before any classpath = a define from the library's extraParams.hxml
					pendingExtraDefines.push(StringTools.trim(line.substr(3)));
				}
			} else if (StringTools.startsWith(line, "-")) {
				pendingExtraArgs.push(line);
			} else {
				pendingClasspaths.push(Path.removeTrailingSlashes(line));
			}
		}
		return libraries;
	}

	static function makeLibrary(name:String, version:String, classpaths:Array<String>,
			extraDefines:Array<String>, extraArgs:Array<String>):ResolvedHaxelib {
		var root = "";
		var includeXml:Null<String> = null;
		for (classpath in classpaths) {
			// include.xml sits at the library ROOT; the classpath is usually <root>/src
			for (candidate in [classpath, Path.directory(classpath)]) {
				var includePath = Path.join([candidate, "include.xml"]);
				if (FileSystem.exists(includePath)) {
					root = candidate;
					includeXml = try File.getContent(includePath) catch (e:Dynamic) null;
					break;
				}
			}
			if (includeXml != null) break;
		}
		if (root == "" && classpaths.length > 0) {
			root = classpaths[0];
		}
		return {name: name, version: version, root: root, classpaths: classpaths,
			includeXml: includeXml, extraDefines: extraDefines, extraArgs: extraArgs};
	}
}
