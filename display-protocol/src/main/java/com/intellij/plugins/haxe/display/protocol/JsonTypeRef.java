package com.intellij.plugins.haxe.display.protocol;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * A {@code haxe.display.JsonModuleTypes.JsonType} kept as raw JSON with typed
 * accessors for what the plugin needs (dot paths, readable signatures). The
 * full typedef family is large and recursive; consumers needing more drill
 * into {@link #args()} directly.
 */
public record JsonTypeRef(String kind, JsonNode args) {

  public static JsonTypeRef of(JsonNode node) {
    if (node == null || node.isNull()) return null;
    return new JsonTypeRef(node.path("kind").asString(""), node.path("args"));
  }

  public boolean isFunction() {
    return "TFun".equals(kind);
  }

  /** The dot path of a TInst/TEnum/TType/TAbstract, e.g. {@code haxe.ds.StringMap}; null for other kinds. */
  public String dotPath() {
    JsonNode path = args.path("path");
    if (path.isMissingNode()) return null;
    String typeName = path.path("typeName").asString("");
    if (typeName.isEmpty()) return null;
    StringBuilder result = new StringBuilder();
    for (JsonNode pack : path.path("pack")) {
      result.append(pack.asString("")).append('.');
    }
    return result.append(typeName).toString();
  }

  /** A human-readable rendering: dot path, function signature, monomorph/dynamic placeholders. */
  public String presentable() {
    return switch (kind) {
      case "TInst", "TEnum", "TType", "TAbstract" -> {
        String path = dotPath();
        yield path != null ? path : "?";
      }
      case "TFun" -> functionSignature();
      case "TDynamic" -> "Dynamic";
      case "TMono" -> "?";
      case "TAnonymous" -> "{ ... }";
      default -> "?";
    };
  }

  private String functionSignature() {
    String parameters = Stream.of(args.path("args"))
      .flatMap(JsonNode::valueStream)
      .map(JsonTypeRef::argumentSignature)
      .collect(Collectors.joining(", "));
    JsonTypeRef ret = of(args.path("ret"));
    return "(" + parameters + ") -> " + (ret != null ? ret.presentable() : "?");
  }

  private static String argumentSignature(JsonNode argument) {
    String name = argument.path("name").asString("");
    JsonTypeRef type = of(argument.path("t"));
    String typeText = type != null ? type.presentable() : "?";
    return name.isEmpty() ? typeText : name + ":" + typeText;
  }
}
