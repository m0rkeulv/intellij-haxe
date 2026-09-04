package limeparser;

import haxe.io.Path;

/**
	Evaluates a lime/openfl project.xml the way lime's ProjectXMLParser does,
	but collects only the build-configuration subset the IDE needs: defines,
	haxedefs, haxelibs (with versions) and source classpaths. Conditional
	if/unless attributes, <section> grouping, <include> files and ${} variable
	substitution are honoured; application/window/asset elements are ignored.

	Semantics mirror lime's isValidElement: an `if` value is an OR ("||") of
	AND groups (space-separated tokens); a token passes when it is "true",
	a known define, a known environment variable or the current command, and
	fails when it is "false" or unknown. `unless` uses the same evaluation and
	excludes the element on a match.
**/
/**
	A haxelib and its transitive dependency chain as `haxelib path` reports it:
	the classpaths belong to this one library; includeXml is the content of its
	include.xml when it ships one (lime merges those as nested projects);
	extraDefines are -D entries from the library's extraParams.hxml (haxelib
	prints that file inline); extraArgs are its remaining compiler arguments
	(--macro lines etc. - not evaluatable statically, surfaced for reference).
**/
typedef ResolvedHaxelib = {
	name:String,
	version:String,
	root:String,
	classpaths:Array<String>,
	includeXml:Null<String>,
	extraDefines:Array<String>,
	extraArgs:Array<String>
}

class ProjectXmlEvaluator {
	/** Resolves an <include path="..."/> reference to file content, or null when unreadable. **/
	public static final NO_INCLUDES:String->Null<String> = path -> null;

	/** Resolves a haxelib (and its transitive deps, in order), or null when unresolvable. **/
	public static final NO_HAXELIBS:(String, String) -> Null<Array<ResolvedHaxelib>> = (name, version) -> null;

	public final defines:Map<String, String> = [];
	public final haxedefs:Map<String, String> = [];
	public final haxelibs:Array<{name:String, version:String}> = [];
	public final sources:Array<String> = [];
	// <app> attributes drive the export layout (path) and the executable name (file);
	// lime's default export root is "bin" ("Export" is only a template convention)
	public var appPath:String = "bin";
	public var appFile:String = "";

	final environment:Map<String, String>;
	final command:String;
	final includeResolver:String->Null<String>;
	final haxelibResolver:(String, String) -> Null<Array<ResolvedHaxelib>>;
	final visitedIncludes:Array<String> = [];
	// asset library handlers: type -> handler haxelib (<library handler="swf" type="swf"/>,
	// registered by openfl's include.xml) and the asset types the project declares
	final libraryHandlers:Map<String, String> = [];
	final declaredAssetTypes:Array<String> = [];
	// library include.xml paths resolve against the library root, not the project
	var pathBase:String = "";

	public function new(seedDefines:Map<String, String>, command:String,
			environment:Map<String, String>, includeResolver:String->Null<String>,
			?haxelibResolver:(String, String) -> Null<Array<ResolvedHaxelib>>) {
		for (name => value in seedDefines) {
			defines.set(name, value);
		}
		this.command = command;
		this.environment = environment;
		this.includeResolver = includeResolver;
		this.haxelibResolver = haxelibResolver != null ? haxelibResolver : NO_HAXELIBS;
	}

	public function parse(content:String):Void {
		var xml = try Xml.parse(content) catch (e:Dynamic) null;
		if (xml == null) return;
		for (element in xml.elements()) {
			// lime accepts both <project> and legacy <xml> roots
			parseElements(element, "");
		}
		resolveAssetHandlers();
	}

	/**
		An asset library whose type has a registered handler pulls that handler
		haxelib into the build (lime runs `haxelib run <handler> process`, whose
		result merges the handler lib itself - e.g. swf for .swf assets). The
		handler map usually comes from a library include.xml, so this resolves
		after the whole project is parsed, like lime's AssetHelper does.
	**/
	function resolveAssetHandlers():Void {
		for (type in declaredAssetTypes) {
			var handler = libraryHandlers.get(type);
			if (handler != null && !Lambda.exists(haxelibs, lib -> lib.name == handler)) {
				resolveAndRegister(handler, "");
			}
		}
	}

	function parseElements(parent:Xml, section:String):Void {
		for (element in parent.elements()) {
			if (!isValidElement(element, section)) continue;

			switch (element.nodeName) {
				case "section":
					parseElements(element, "");
				case "include":
					parseInclude(element);
				case "set":
					var name = element.get("name");
					var value = substitute(orEmpty(element.get("value")));
					defines.set(name, value);
					environment.set(name, value);
				case "unset":
					defines.remove(element.get("name"));
					environment.remove(element.get("name"));
				case "define":
					var name = element.get("name");
					var value = substitute(orEmpty(element.get("value")));
					defines.set(name, value);
					haxedefs.set(name, value);
					environment.set(name, value);
				case "undefine":
					defines.remove(element.get("name"));
					haxedefs.remove(element.get("name"));
					environment.remove(element.get("name"));
				case "setenv":
					var name = element.get("name");
					var value = substitute(orEmpty(element.get("value")));
					environment.set(name, value);
					defines.set(name, value);
				case "haxedef":
					haxedefs.set(substitute(element.get("name")), substitute(orEmpty(element.get("value"))));
				case "haxelib":
					parseHaxelib(element);
				case "source", "classpath":
					var path = element.exists("path") ? element.get("path") : element.get("name");
					if (path != null) {
						sources.push(rebase(substitute(path)));
					}
				case "app":
					if (element.exists("path")) appPath = substitute(element.get("path"));
					if (element.exists("file")) appFile = substitute(element.get("file"));
				case "library":
					if (element.exists("handler") && element.exists("type")) {
						libraryHandlers.set(substitute(element.get("type")), substitute(element.get("handler")));
					} else {
						var type = element.exists("type") ? substitute(element.get("type"))
							: element.exists("path") ? Path.extension(substitute(element.get("path"))).toLowerCase() : "";
						if (type != "" && !declaredAssetTypes.contains(type)) {
							declaredAssetTypes.push(type);
						}
					}
				default:
					// app/meta/window/assets/icon/... carry no build configuration
			}
		}
	}

	function parseHaxelib(element:Xml):Void {
		var name = substitute(element.get("name"));
		if (name == null || name == "") return;
		var version = substitute(orEmpty(element.get("version")));
		resolveAndRegister(name, version);
	}

	function resolveAndRegister(name:String, version:String):Void {
		if (Lambda.exists(haxelibs, lib -> lib.name == name)) return;

		var resolved = haxelibResolver(name, version);
		if (resolved == null) {
			registerHaxelib(name, version, version);
			return;
		}
		// the resolver returns the library plus its transitive dependency chain;
		// each library's include.xml merges as a nested project (lime's
		// HXProject.fromHaxelib) - it can add more haxelibs, haxedefs and sources
		for (library in resolved) {
			if (Lambda.exists(haxelibs, lib -> lib.name == library.name)) continue;
			// only the project's own pin is a pin: the resolved version is what
			// the checkout's haxelib.json says, and echoing it as a request
			// would make a later `haxelib path name:version` pick that release
			// over the repository's current (git/dev) selection
			var declaredVersion = library.name == name ? version : "";
			registerHaxelib(library.name, declaredVersion, library.version);
			for (classpath in library.classpaths) {
				if (!sources.contains(classpath)) {
					sources.push(classpath);
				}
			}
			// extraParams.hxml -D entries are real compile-context defines
			for (extraDefine in library.extraDefines) {
				var pair = extraDefine.split("=");
				var value = pair.length > 1 ? pair.slice(1).join("=") : "";
				defines.set(pair[0], value);
				haxedefs.set(pair[0], value);
			}
			if (library.includeXml != null) {
				parseLibraryInclude(library.includeXml, library.root);
			}
		}
	}

	/** The listed version is the project's DECLARED pin (empty when none); the define carries the resolved one. **/
	function registerHaxelib(name:String, declaredVersion:String, resolvedVersion:String):Void {
		haxelibs.push({name: name, version: declaredVersion});
		// lime defines each haxelib's name so later conditions can test for it
		// (why if="openfl" works below a <haxelib name="openfl"/> line)
		if (!defines.exists(name)) {
			defines.set(name, resolvedVersion);
		}
	}

	function parseLibraryInclude(content:String, libraryRoot:String):Void {
		var xml = try Xml.parse(content) catch (e:Dynamic) null;
		if (xml == null) return;
		var previousBase = pathBase;
		pathBase = libraryRoot;
		for (root in xml.elements()) {
			parseElements(root, "");
		}
		pathBase = previousBase;
	}

	function rebase(path:String):String {
		if (pathBase == "" || Path.isAbsolute(path)) return path;
		return Path.join([pathBase, path]);
	}

	function parseInclude(element:Xml):Void {
		var path = element.exists("path") ? element.get("path") : element.get("name");
		if (path == null) return;
		path = substitute(path);
		if (visitedIncludes.contains(path)) return;
		visitedIncludes.push(path);

		var content = includeResolver(path);
		if (content == null) return;
		var xml = try Xml.parse(content) catch (e:Dynamic) null;
		if (xml == null) return;
		var section = orEmpty(element.get("section"));
		for (root in xml.elements()) {
			parseElements(root, section);
		}
	}

	function isValidElement(element:Xml, section:String):Bool {
		var ifValue = element.get("if");
		if (ifValue != null && !matchesConditions(ifValue)) {
			return false;
		}

		var unlessValue = element.get("unless");
		if (unlessValue != null && matchesConditions(unlessValue)) {
			return false;
		}

		if (section != "") {
			if (element.nodeName != "section") return false;
			if (!element.exists("id")) return false;
			if (substitute(element.get("id")) != section) return false;
		}

		return true;
	}

	// OR over "||" segments, each segment an AND over space-separated tokens
	function matchesConditions(value:String):Bool {
		var anyMatched = false;
		for (segment in substitute(value).split("||")) {
			var allMatched = true;
			for (token in substitute(segment).split(" ")) {
				var check = StringTools.trim(substitute(token));
				if (check == "false") {
					allMatched = false;
				} else if (check != "" && check != "true"
						&& !defines.exists(check)
						&& !environment.exists(check)
						&& check != command) {
					allMatched = false;
				}
			}
			if (allMatched) {
				anyMatched = true;
			}
		}
		return anyMatched;
	}

	// ${name} variable reference; the name group excludes closing braces
	static final VAR_REFERENCE = ~/\$\{([^}]+)\}/;

	function substitute(value:Null<String>):String {
		if (value == null) return "";
		var result = value;
		while (VAR_REFERENCE.match(result)) {
			var name = VAR_REFERENCE.matched(1);
			var replacement = defines.exists(name) ? defines.get(name)
				: environment.exists(name) ? environment.get(name) : "";
			result = VAR_REFERENCE.matchedLeft() + replacement + VAR_REFERENCE.matchedRight();
		}
		return result;
	}

	static function orEmpty(value:Null<String>):String {
		return value == null ? "" : value;
	}
}
