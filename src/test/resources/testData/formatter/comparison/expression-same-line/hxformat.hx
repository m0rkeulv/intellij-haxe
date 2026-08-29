class Main {
	static function main() {
		var mode = "dev";
		var level = if (mode == "dev") 1 else 2;
		var label = try Std.string(level) catch (e:Dynamic) "?";
		var big = switch (mode) {
			case "dev": 10;
			default: 20;
		};
		trace(level + big + label);
	}
}
