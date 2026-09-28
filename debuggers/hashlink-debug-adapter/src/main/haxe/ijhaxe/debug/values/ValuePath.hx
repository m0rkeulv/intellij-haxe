package ijhaxe.debug.values;

/**
	A variable path such as `obj.items[3].name`: a root name followed by field
	and index accessors. The evaluator resolves it to a slot in debuggee memory.
**/
class ValuePath {
	public final root:String;
	public final accessors:Array<PathAccessor>;

	public function new(root:String, accessors:Array<PathAccessor>) {
		this.root = root;
		this.accessors = accessors;
	}

	/**
		The path as text for display and error messages (`obj.field[3]`).
	**/
	public function display():String {
		var s = root;
		for (a in accessors) {
			s += switch (a) {
				case Field(n): "." + n;
				case Index(i): "[" + i + "]";
			}
		}
		return s;
	}

	/**
		A copy of this path with one more field accessor appended.
	**/
	public function plus(field:String):ValuePath {
		return new ValuePath(root, accessors.concat([Field(field)]));
	}

	/**
		Parses a path of this grammar, or returns null for any other text
		(operators, calls, literals):

		```
		path     = ident accessor*
		accessor = "." ident | "[" digits "]"
		ident    = [A-Za-z_$][A-Za-z0-9_$]*
		```

		TODO: only tests call this; evaluate requests parse through ExprParser. Remove it with its test.
	**/
	public static function parse(expression:Null<String>):Null<ValuePath> {
		if (expression == null) {
			return null;
		}
		var s = StringTools.trim(expression);
		if (s.length == 0) {
			return null;
		}
		var pos = 0;

		inline function peek():Int {
			return pos < s.length ? StringTools.fastCodeAt(s, pos) : -1;
		}

		function isIdentStart(c:Int):Bool {
			return (c >= "a".code && c <= "z".code) || (c >= "A".code && c <= "Z".code) || c == "_".code || c == "$".code;
		}
		function isIdentPart(c:Int):Bool {
			return isIdentStart(c) || (c >= "0".code && c <= "9".code);
		}
		function readIdent():Null<String> {
			if (!isIdentStart(peek())) {
				return null;
			}
			var start = pos;
			while (isIdentPart(peek())) {
				pos++;
			}
			return s.substring(start, pos);
		}

		var root = readIdent();
		if (root == null) {
			return null;
		}
		var accessors:Array<PathAccessor> = [];
		while (pos < s.length) {
			switch (peek()) {
				case ".".code:
					pos++;
					var name = readIdent();
					if (name == null) {
						return null;
					}
					accessors.push(Field(name));
				case "[".code:
					pos++;
					var start = pos;
					while (peek() >= "0".code && peek() <= "9".code) {
						pos++;
					}
					if (pos == start || peek() != "]".code) {
						return null;
					}
					accessors.push(Index(Std.parseInt(s.substring(start, pos))));
					pos++;
				default:
					return null; // an operator, a call, whitespace inside the path, ...
			}
		}
		return new ValuePath(root, accessors);
	}
}
