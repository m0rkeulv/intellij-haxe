class Main {
	static function main() {
		// every argument a chain: the operands total past 120 -> one operand per line
		cairo.curveTo(scaledControlX1 - offsetX, scaledControlY1 - offsetY, scaledControlX2 - offsetX, scaledControlY2 - offsetY, scaledAnchorX - offsetX, scaledAnchorY - offsetY);
		// three chains totalling under 120 keep their shape; the plain arguments fill
		cairo.curveTo(scaledControlX1 - offsetX, scaledControlY1 - offsetY, scaledControlX2 - offsetX, trailingArgumentNumberOne, trailingArgumentNumberTwo, trailingArgumentNumberThree, trailingArgumentNumberFour);
		// six tiny chains stay under the total too
		cairo.curveTo(aa - b, cc - d, ee - f, gg - h, ii - j, kk - l, trailingArgumentNumberOne, trailingArgumentNumberTwo, trailingArgumentNumberThree, trailingArgumentNumberFour);
		// one chain ending inside the margin: nothing
		cairo.curveTo(xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx - bb, trailingArgumentNumberOne, trailingArgumentNumberTwo, trailingArgumentNumberThree);
		// one chain ending past the margin: the overflowing operator breaks
		cairo.curveTo(xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx - bb, trailingArgumentNumberOne, trailingArgumentNumberTwo, trailingArgumentNumberThree);
		// five operands past 120 on a short line: one per line
		var s = aaaaaaaaaaaaaaaaaaaaaaaaa + bbbbbbbbbbbbbbbbbbbbbbbbb + ccccccccccccccccccccccccc + ddddddddddddddddddddddddd + eeeeeeeeeeeeeeeeeeeeeeeee;
		// five operands under 120: nothing
		var t = aaaaaaaaaaaaaaaaaaaa + bbbbbbbbbbbbbbbbbbbb + cccccccccccccccccccc + dddddddddddddddddddd + eeeeeeeeeeeeeeeeeeee;
		// six operands on a line past the margin: filled
		var total = aaaaaaaaaaaaaaaaaaaaaaaaa - bbbbbbbbbbbbbbbbbbbbbbbbb + ccccccccccccccccccccccccc - ddddddddddddddddddddddddd + eeeeeeeeeeeeeeeeeeeeeeeee - fffff;
	}
}
