class Main {
	static function main() {
		var width = Math.ceil(bounds.right) - horizontalOffset,
		height = Math.ceil(bounds.bottom) - verticalOffset;
		var top = Math.floor(bounds.top) - verticalOffset, bottom = Math.floor(bounds.bottom) - verticalOffset;
		var a = 1, b = 2;
		var uvx1:Float, uvy1:Float, uvx2:Float, uvy2:Float, uvx3:Float, uvy3:Float;
		trace(width + height + top + bottom + a + b + uvx1 + uvy1 + uvx2 + uvy2 + uvx3 + uvy3);
	}
}
