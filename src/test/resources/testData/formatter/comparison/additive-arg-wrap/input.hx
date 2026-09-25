class Main {
	static function main() {
		var offsetX = 1.0;
		var offsetY = 2.0;
		switch (kind) {
			case CUBIC:
				var c = readCubicCurve();
				surface.curveTo(c.controlX1
					- offsetX, c.controlY1
					- offsetY, c.controlX2
					- offsetX, c.controlY2
					- offsetY, c.anchorX
					- offsetX,
					c.anchorY
					- offsetY);
			case SCALED:
				surface.curveTo(scaledControlX1
					- offsetX, scaledControlY1
					- offsetY, scaledControlX2
					- offsetX, scaledControlY2
					- offsetY,
					scaledAnchorX
					- offsetX, scaledAnchorY
					- offsetY);
			default:
		}
	}
}
