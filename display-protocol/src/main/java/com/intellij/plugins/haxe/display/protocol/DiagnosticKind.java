package com.intellij.plugins.haxe.display.protocol;

/**
 * Wire codes of std {@code haxe.display.DiagnosticKind}. The {@code args}
 * payload differs per kind (see the std typedef): UNRESOLVED_IDENTIFIER
 * carries import/typo suggestions, COMPILER_ERROR/PARSER_ERROR a message
 * string, REMOVABLE_CODE a description+range, MISSING_FIELDS the structured
 * implement-members data.
 */
public enum DiagnosticKind {
  UNUSED_IMPORT(0),
  UNRESOLVED_IDENTIFIER(1),
  COMPILER_ERROR(2),
  REMOVABLE_CODE(3),
  PARSER_ERROR(4),
  DEPRECATION_WARNING(5),
  INACTIVE_BLOCK(6),
  MISSING_FIELDS(7),
  UNKNOWN(-1);

  private final int code;

  DiagnosticKind(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  public static DiagnosticKind fromCode(int code) {
    for (DiagnosticKind kind : values()) {
      if (kind.code == code) return kind;
    }
    return UNKNOWN;
  }
}
