package com.intellij.plugins.haxe.display.protocol;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The args of a MISSING_FIELDS diagnostic: the type the fields are missing
 * from, its file, and one entry per cause. A cause is one of
 * {@code ImplementedInterface}, {@code AbstractParent} (members the parent
 * declares and the type lacks), {@code PropertyAccessor} (an accessor a
 * property names), {@code FieldAccess} (a call or access on a member the
 * type does not have) and {@code FinalFields} (final fields no constructor
 * initializes - the entry's own field list is then empty and the fields sit
 * in the cause).
 */
public record MissingFields(String typeName, String moduleFile, List<Entry> entries) {

  public record Entry(String causeKind, List<MissingField> fields, List<MissingField> causeFields) {
    public boolean isFinalFields() {
      return "FinalFields".equals(causeKind);
    }

    public boolean isFieldAccess() {
      return "FieldAccess".equals(causeKind);
    }
  }

  /**
   * One missing class field as the compiler typed it: {@code fieldKind} is
   * FMethod, FVar or FProp; a variable's read and write access are the wire
   * kinds (AccNormal, AccCall, AccNever, AccNo, ...); {@code position} is the
   * declaration's start offset in its own file, -1 when unknown.
   */
  public record MissingField(String name, JsonTypeRef type, String fieldKind, boolean isPublic, boolean isStatic,
                             boolean unique, String readAccess, String writeAccess, int position) {
    public boolean isMethod() {
      return "FMethod".equals(fieldKind);
    }
  }

  /** Null when the args are not the missing-fields shape. */
  public static MissingFields fromJson(JsonNode args) {
    if (args == null || !args.isObject()) return null;
    List<Entry> entries = new ArrayList<>();
    for (JsonNode entry : args.path("entries")) {
      entries.add(decodeEntry(entry));
    }
    String typeName = args.path("moduleType").path("name").asString("");
    return new MissingFields(typeName, args.path("moduleFile").asString(""), List.copyOf(entries));
  }

  private static Entry decodeEntry(JsonNode entry) {
    List<MissingField> fields = new ArrayList<>();
    for (JsonNode wrapped : entry.path("fields")) {
      fields.add(decodeField(wrapped.path("field"), wrapped.path("unique").asBoolean(true)));
    }
    JsonNode cause = entry.path("cause");
    List<MissingField> causeFields = new ArrayList<>();
    for (JsonNode field : cause.path("args").path("fields")) {
      causeFields.add(decodeField(field, true));
    }
    return new Entry(cause.path("kind").asString(""), List.copyOf(fields), List.copyOf(causeFields));
  }

  private static MissingField decodeField(JsonNode field, boolean unique) {
    JsonNode kind = field.path("kind");
    JsonNode access = kind.path("args");
    // the class field scope: 0 static, 1 member, 2 constructor
    boolean isStatic = field.path("scope").asInt(1) == 0;
    return new MissingField(field.path("name").asString(""),
                            JsonTypeRef.of(field.path("type")),
                            kind.path("kind").asString(""),
                            field.path("isPublic").asBoolean(false),
                            isStatic,
                            unique,
                            access.path("read").path("kind").asString(""),
                            access.path("write").path("kind").asString(""),
                            field.path("pos").path("min").asInt(-1));
  }
}
