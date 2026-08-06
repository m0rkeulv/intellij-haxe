package com.intellij.plugins.haxe.display.protocol;

/**
 * The useful subset of a {@code display/hover} result: the hovered range, the
 * item kind ({@code Local}, {@code ClassField}, {@code Type}, ...), the
 * resolved type and the documentation, if any.
 */
public record HoverInfo(Range range, String itemKind, JsonTypeRef type, String documentation) {
}
