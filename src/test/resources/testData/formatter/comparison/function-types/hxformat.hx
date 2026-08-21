class Main {
	static function main() {
		var twice:(value:Int) -> Int = value -> value * 2;
		var join:(a:String, b:String) -> String = (a, b) -> a + b;
		trace(twice(2), join("x", "y"));
	}
}
