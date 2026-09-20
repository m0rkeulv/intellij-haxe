class Main {
	static function main() {
		var width = Math.ceil(bounds.right) - horizontalOffset,
			height = Math.ceil(bounds.bottom) - verticalOffset;
		var top = Math.floor(bounds.top) - verticalOffset,
			bottom = Math.floor(bounds.bottom) - verticalOffset;
		var a = 1, b = 2;
		trace(width + height + top + bottom + a + b);
	}
}
