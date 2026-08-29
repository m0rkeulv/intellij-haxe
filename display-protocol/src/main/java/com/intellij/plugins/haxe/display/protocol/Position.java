package com.intellij.plugins.haxe.display.protocol;

import tools.jackson.databind.JsonNode;

/**
 * A position in a JSON-RPC display RESULT: 0-BASED line and character. The
 * std {@code Position.hx} docstrings claim 1-based, but the wire values are
 * converted for LSP — verified live against haxe 4.3.7.
 */
public record Position(int line, int character) {

  /** Decodes the wire {@code {line, character}} object; absent fields read as 0. */
  public static Position fromJson(JsonNode node) {
    return new Position(node.path("line").asInt(0), node.path("character").asInt(0));
  }
}
