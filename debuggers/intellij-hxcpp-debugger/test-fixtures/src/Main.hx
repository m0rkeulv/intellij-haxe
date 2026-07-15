/**
	Debuggee fixture: compiled with `-lib intellij-hxcpp-debug-server -debug`
	(see fixture.hxml). Line numbers will become load-bearing test constants
	once the breakpoint milestone lands — extend at the END of the file.
**/
class Main {
	static function main():Void {
		Sys.println("fixture-start");
		var total = 0;
		for (i in 0...3) {
			total = add(total, i);
		}
		Sys.println("fixture-total:" + total);
	}

	static function add(current:Int, amount:Int):Int {
		return current + amount;
	}
}
