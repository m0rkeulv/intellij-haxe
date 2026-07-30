package com.intellij.plugins.haxe.display.protocol;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One entry of a {@code display/diagnostics} result. {@code args} stays raw
 * JSON — its shape depends on {@link DiagnosticKind}; use the typed accessors
 * for the common cases.
 */
public record Diagnostic(DiagnosticKind kind,
                         Range range,
                         DiagnosticSeverity severity,
                         JsonNode args,
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
}
