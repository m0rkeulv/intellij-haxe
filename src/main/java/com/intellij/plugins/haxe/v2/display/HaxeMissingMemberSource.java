package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.display.protocol.MissingFields;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The source text of the members a MISSING_FIELDS entry asks for, as the
 * compiler typed them: method stubs that throw until implemented (an empty
 * body for Void), properties with their access pair, plain variables, and
 * for a class whose final fields go uninitialized a constructor taking and
 * assigning each of them in declaration order. An abstract parent's methods
 * are implemented without {@code override} - the compiler rejects it on an
 * abstract parent field.
 */
final class HaxeMissingMemberSource {

  private static final String STUB_BODY = "{\n\tthrow new haxe.exceptions.NotImplementedException();\n}";
  private static final String EMPTY_BODY = "{\n}";

  private HaxeMissingMemberSource() {
  }

  /** One declaration per member, in the entry's order. */
  @NotNull
  static List<String> declarationsOf(@NotNull MissingFields.Entry entry) {
    if (entry.isFinalFields()) return List.of(constructorText(entry.causeFields()));
    List<String> declarations = new ArrayList<>();
    for (MissingFields.MissingField field : entry.fields()) {
      declarations.add(field.isMethod() ? methodText(field) : variableText(field));
    }
    return declarations;
  }

  @NotNull
  private static String methodText(@NotNull MissingFields.MissingField field) {
    String returnType = HaxeTypeSyntax.returnTypeText(field.type());
    String body = "Void".equals(returnType) ? EMPTY_BODY : STUB_BODY;
    String parameters = field.type() != null && field.type().isFunction() ? HaxeTypeSyntax.parameterListText(field.type()) : "";
    return modifiers(field) + "function " + field.name() + "(" + parameters + "):" + returnType + " " + body;
  }

  @NotNull
  private static String variableText(@NotNull MissingFields.MissingField field) {
    String access = accessPair(field);
    return modifiers(field) + "var " + field.name() + access + ":" + HaxeTypeSyntax.safeTypeText(field.type()) + ";";
  }

  @NotNull
  private static String constructorText(@NotNull List<MissingFields.MissingField> finalFields) {
    List<MissingFields.MissingField> ordered = new ArrayList<>(finalFields);
    ordered.sort(Comparator.comparingInt(MissingFields.MissingField::position));
    List<String> parameters = new ArrayList<>();
    StringBuilder assignments = new StringBuilder();
    for (MissingFields.MissingField field : ordered) {
      parameters.add(field.name() + ":" + HaxeTypeSyntax.safeTypeText(field.type()));
      assignments.append("\tthis.").append(field.name()).append(" = ").append(field.name()).append(";\n");
    }
    return "public function new(" + String.join(", ", parameters) + ") {\n" + assignments + "}";
  }

  @NotNull
  private static String modifiers(@NotNull MissingFields.MissingField field) {
    return (field.isPublic() ? "public " : "") + (field.isStatic() ? "static " : "");
  }

  /** {@code (get, never)} for a property; nothing for a plain variable. */
  @NotNull
  private static String accessPair(@NotNull MissingFields.MissingField field) {
    String read = accessText(field.readAccess(), "get");
    String write = accessText(field.writeAccess(), "set");
    if (read == null && write == null) return "";
    return "(" + (read != null ? read : "default") + ", " + (write != null ? write : "default") + ")";
  }

  /** The source spelling of a wire access kind; null for the plain access that needs no pair. */
  private static String accessText(@NotNull String wireKind, @NotNull String accessor) {
    return switch (wireKind) {
      case "AccCall" -> accessor;
      case "AccNever" -> "never";
      case "AccNo" -> "null";
      default -> null;
    };
  }
}
