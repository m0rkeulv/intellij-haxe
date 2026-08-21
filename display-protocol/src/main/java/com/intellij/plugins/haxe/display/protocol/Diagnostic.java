package com.intellij.plugins.haxe.display.protocol;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One entry of a {@code display/diagnostics} result. {@code args} stays raw
 * JSON — its shape depends on {@link DiagnosticKind}; use the typed accessors
 * for the common cases. {@code code} is the LSP-style stable identifier the
 * typedef declares as optional; null when the compiler sends none.
 */
public record Diagnostic(DiagnosticKind kind,
                         Range range,
                         DiagnosticSeverity severity,
                         JsonNode args,
                         String code,
                         List<RelatedInformation> relatedInformation) {

  public record RelatedInformation(Location location, String message, int depth) {
  }

  /** The message of a COMPILER_ERROR / PARSER_ERROR / DEPRECATION_WARNING (args = plain string). */
  public String messageArg() {
    return args != null && args.isString() ? args.asString() : "";
  }

  /** Identifier suggestions of an UNRESOLVED_IDENTIFIER: kind 0 = import candidate, 1 = typo correction. */
  public record IdentifierSuggestion(int kind, String name) {
    public boolean isImportCandidate() {
      return kind == 0;
    }
  }

  public List<IdentifierSuggestion> suggestionArgs() {
    if (args == null || !args.isArray()) return List.of();
    return args.valueStream()
      .map(node -> new IdentifierSuggestion(node.path("kind").asInt(-1), node.path("name").asString("")))
      .toList();
  }

  /** The REMOVABLE_CODE description (args = {description, range}); empty when absent. */
  public String descriptionArg() {
    return args != null ? args.path("description").asString("") : "";
  }

  /**
   * The REMOVABLE_CODE deletion range from args — the span to REMOVE, which
   * may differ from the diagnostic's display range; null when the compiler
   * supplies none (callers fall back to the display range).
   */
  public Range removableRangeArg() {
    if (args == null) return null;
    JsonNode range = args.path("range");
    if (range.isMissingNode() || range.isNull()) return null;
    return new Range(positionOf(range.path("start")), positionOf(range.path("end")));
  }

  /**
   * The haxe 5 replacement text for the removable span ({@code newCode} on
   * the ReplaceableCode args); null when absent or empty — both mean plain
   * removal.
   */
  public String newCodeArg() {
    if (args == null) return null;
    String newCode = args.path("newCode").asString("");
    return newCode.isEmpty() ? null : newCode;
  }

  private static Position positionOf(JsonNode node) {
    return new Position(node.path("line").asInt(0), node.path("character").asInt(0));
  }
}
