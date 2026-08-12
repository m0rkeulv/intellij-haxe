package com.intellij.plugins.haxe.display.protocol;

/** A file plus range, as returned by definition/references/implementation. */
public record Location(String file, Range range) {
}
