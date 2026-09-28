package com.intellij.plugins.haxe.display.protocol;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One entry of a {@code display/diagnostics} result.
 *
 * {@code args} stays raw JSON because its shape depends on the
 * {@link DiagnosticKind}; the {@code ...Arg} accessors read the common shapes.
 * {@code code} is the optional LSP-style identifier of the diagnostic, null
 * when the compiler sends none (haxe 4 never does).
 */
public record Diagnostic(DiagnosticKind kind,
                         Range range,
                         DiagnosticSeverity severity,
                         JsonNode args,
                         String code) {

  /** The message of a COMPILER_ERROR, PARSER_ERROR or DEPRECATION_WARNING, whose args are a plain string. */
  public String messageArg() {
    return args != null && args.isString() ? args.asString() : "";
  }

  /** One suggestion of an UNRESOLVED_IDENTIFIER: {@code kind} 0 is an import candidate, 1 a typo correction. */
  public record IdentifierSuggestion(int kind, String name) {
    public boolean isImportCandidate() {
      return kind == 0;
    }
  }

  /** The suggestions of an UNRESOLVED_IDENTIFIER, whose args are an array of them. */
  public List<IdentifierSuggestion> suggestionArgs() {
    if (args == null || !args.isArray()) return List.of();
    return args.valueStream()
      .map(node -> new IdentifierSuggestion(node.path("kind").asInt(-1), node.path("name").asString("")))
      .toList();
  }

  /** The typed args of a MISSING_FIELDS entry; null for any other shape. */
  public MissingFields missingFieldsArg() {
    return MissingFields.fromJson(args);
  }

  /** The description of a REMOVABLE_CODE entry; empty when absent. */
  public String descriptionArg() {
    return args != null ? args.path("description").asString("") : "";
  }

  /**
   * The span a REMOVABLE_CODE fix should delete. It can be wider than the
   * highlighted {@link #range()}. Null when the compiler supplies none.
   */
  public Range removableRangeArg() {
    if (args == null) return null;
    JsonNode range = args.path("range");
    if (range.isMissingNode() || range.isNull()) return null;
    return Range.fromJson(range);
  }

  /**
   * The text haxe 5 offers in place of the removable span ({@code newCode});
   * null when absent or empty, both of which mean plain removal.
   */
  public String newCodeArg() {
    if (args == null) return null;
    String newCode = args.path("newCode").asString("");
    return newCode.isEmpty() ? null : newCode;
  }
}
