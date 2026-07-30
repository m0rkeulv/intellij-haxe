package com.intellij.plugins.haxe.display.protocol;

/**
 * A position in a JSON-RPC display RESULT: 0-BASED line and character. The
 * std {@code Position.hx} docstrings claim 1-based, but the wire values are
 * converted for LSP — verified live against haxe 4.3.7.
 */
public record Position(int line, int character) {
}
