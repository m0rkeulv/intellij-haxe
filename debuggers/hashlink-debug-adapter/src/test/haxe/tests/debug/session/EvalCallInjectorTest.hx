package tests.debug.session;

import ijhaxe.debug.Pointer;
import ijhaxe.debug.session.EvalCallInjector;

import haxe.Int64;

class EvalCallInjectorTest {
	// Esp residues modulo 256: aligned, dword-offset, byte-offset and the top of the range
	static final ESP_RESIDUES = [0x00, 0x04, 0x08, 0xF8, 0xFC, 0xFF];

	public static function run(assert:Assert):Void {
		scratchStackTopStaysBelowTheInterruptedFrame(assert);
	}

	// A 256-aligned Esp once made the scratch top equal Esp, so the x86 float
	// return spilled onto the interrupted frame's top slot; every residue must
	// now leave a gap of at least 256 bytes.
	static function scratchStackTopStaysBelowTheInterruptedFrame(assert:Assert):Void {
		var alignedEsp = Int64.make(0x7FFF, 0x00010000);
		for (residue in ESP_RESIDUES) {
			var esp = Int64.add(alignedEsp, Int64.ofInt(residue));

			var top = EvalCallInjector.scratchStackTop(esp);
			var gap = Int64.toInt(Int64.sub(esp, top));

			assert.equals(0, top.low & 0xFF, 'top is 256-byte aligned for residue $residue');
			assert.isTrue(gap >= 0x100 && gap < 0x200, 'top sits 256 to 511 bytes below esp for residue $residue (gap $gap)');
		}
	}
}
