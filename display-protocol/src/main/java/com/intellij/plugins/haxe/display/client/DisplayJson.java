package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.*;
import com.intellij.plugins.haxe.display.protocol.server.*;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.display.transport.MalformedPayloadException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encodes JSON-RPC display requests and decodes response envelopes into the
 * protocol DTOs. The envelope nests twice: the JSON-RPC {@code result} wraps
 * the std {@code Response<T>} whose own {@code result} holds the data.
 */
public final class DisplayJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build();

  private DisplayJson() {
  }

  public static String encodeRequest(String method, Map<String, Object> params) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("jsonrpc", "2.0");
    envelope.put("id", 1);
    envelope.put("method", method);
    envelope.put("params", params);
    return MAPPER.writeValueAsString(envelope);
  }

  public static JsonNode unwrap(String payload) throws DisplayRequestException {
    JsonNode root;
    try {
      root = MAPPER.readTree(payload);
    } catch (RuntimeException e) {
      throw new MalformedPayloadException("Malformed display response: " + abbreviate(payload), e);
    }

    JsonNode error = root.path("error");
    if (!error.isMissingNode()) {
      String errorMessage = error.path("message").asString("unknown error");
      int errorCode = error.path("code").asInt(-1);
      throw new DisplayRequestException("Display request failed: " + errorMessage + " (code " + errorCode + ")");
    }
    return root.path("result").path("result");
  }

  private static String abbreviate(String text) {
    return text.length() > 200 ? text.substring(0, 200) + "..." : text;
  }

  // --- result decoders ---

  public static InitializeResult decodeInitialize(JsonNode data) {
    List<String> methods = new ArrayList<>();
    for (JsonNode method : data.path("methods")) {
      methods.add(method.asString(""));
    }
    return new InitializeResult(
      decodeSemVer(data.path("protocolVersion")),
      decodeSemVer(data.path("haxeVersion")),
      List.copyOf(methods));
  }

  private static InitializeResult.SemVer decodeSemVer(JsonNode node) {
    return new InitializeResult.SemVer(
      node.path("major").asInt(0),
      node.path("minor").asInt(0),
      node.path("patch").asInt(0),
      node.path("pre").asString(null),
      node.path("build").asString(null));
  }

  public static List<FileDiagnostics> decodeDiagnostics(JsonNode data) {
    List<FileDiagnostics> files = new ArrayList<>();
    for (JsonNode fileEntry : data) {
      List<Diagnostic> diagnostics = new ArrayList<>();
      for (JsonNode entry : fileEntry.path("diagnostics")) {
        diagnostics.add(decodeDiagnostic(entry));
      }
      files.add(new FileDiagnostics(fileEntry.path("file").asString(""), List.copyOf(diagnostics)));
    }
    return List.copyOf(files);
  }

  private static Diagnostic decodeDiagnostic(JsonNode entry) {
    List<Diagnostic.RelatedInformation> related = new ArrayList<>();
    for (JsonNode info : entry.path("relatedInformation")) {
      related.add(new Diagnostic.RelatedInformation(
        decodeLocation(info.path("location")),
        info.path("message").asString(""),
        info.path("depth").asInt(0)));
    }
    return new Diagnostic(
      DiagnosticKind.fromCode(entry.path("kind").asInt(-1)),
      decodeRange(entry.path("range")),
      DiagnosticSeverity.fromCode(entry.path("severity").asInt(-1)),
      entry.path("args"),
      List.copyOf(related));
  }

  public static List<Location> decodeLocations(JsonNode data) {
    List<Location> locations = new ArrayList<>();
    for (JsonNode entry : data) {
      locations.add(decodeLocation(entry));
    }
    return List.copyOf(locations);
  }

  private static Location decodeLocation(JsonNode node) {
    return new Location(node.path("file").asString(""), decodeRange(node.path("range")));
  }

  private static Range decodeRange(JsonNode node) {
    return new Range(decodePosition(node.path("start")), decodePosition(node.path("end")));
  }

  private static Position decodePosition(JsonNode node) {
    return new Position(node.path("line").asInt(0), node.path("character").asInt(0));
  }

  /** Null when there is nothing under the cursor (hover result is nullable). */
  public static HoverInfo decodeHover(JsonNode data) {
    if (data.isNull() || data.isMissingNode()) return null;
    JsonNode item = data.path("item");
    return new HoverInfo(
      decodeRange(data.path("range")),
      item.path("kind").asString(""),
      JsonTypeRef.of(item.path("type")),
      data.path("documentation").asString(null));
  }

  public static List<HaxeServerContext> decodeContexts(JsonNode data) {
    List<HaxeServerContext> contexts = new ArrayList<>();
    for (JsonNode entry : data) {
      contexts.add(decodeContext(entry));
    }
    return List.copyOf(contexts);
  }

  public static HaxeServerContext decodeContext(JsonNode entry) {
    List<String> classPaths = new ArrayList<>();
    for (JsonNode path : entry.path("classPaths")) {
      classPaths.add(path.asString(""));
    }
    Map<String, String> defines = new LinkedHashMap<>();
    for (JsonNode define : entry.path("defines")) {
      defines.put(define.path("key").asString(""), define.path("value").asString(""));
    }
    int index = entry.path("index").asInt(0);
    String desc = entry.path("desc").asString("");
    String signature = entry.path("signature").asString("");
    String platform = entry.path("platform").asString("");
    return new HaxeServerContext(index, desc, signature, platform, List.copyOf(classPaths), defines);
  }

  public static ServerMemory decodeServerMemory(JsonNode data) {
    List<ServerMemory.ContextSize> contexts = new ArrayList<>();
    for (JsonNode entry : data.path("contexts")) {
      contexts.add(new ServerMemory.ContextSize(decodeContext(entry.path("context")),
                                                entry.path("size").asLong(0)));
    }
    long totalCache = data.path("memory").path("totalCache").asLong(0);
    return new ServerMemory(totalCache, List.copyOf(contexts));
  }

  public static List<MetadataEntry> decodeMetadataList(JsonNode data) {
    List<MetadataEntry> entries = new ArrayList<>();
    for (JsonNode entry : data) {
      String name = entry.path("name").asString("");
      String doc = entry.path("doc").asString("");
      boolean internal = entry.path("internal").asBoolean(false);
      entries.add(new MetadataEntry(name, doc, internal));
    }
    return List.copyOf(entries);
  }

  public static List<String> decodeStringList(JsonNode data) {
    List<String> values = new ArrayList<>();
    for (JsonNode entry : data) {
      values.add(entry.asString(""));
    }
    return List.copyOf(values);
  }

  public static ModuleInfo decodeModule(JsonNode data) {
    List<String> types = new ArrayList<>();
    for (JsonNode type : data.path("types")) {
      types.add(type.path("typeName").asString(""));
    }
    return new ModuleInfo(
      data.path("file").asString(""),
      data.path("sign").asString(""),
      List.copyOf(types),
      decodeModuleIds(data.path("dependencies")),
      decodeModuleIds(data.path("dependents")));
  }

  private static List<String> decodeModuleIds(JsonNode node) {
    List<String> paths = new ArrayList<>();
    for (JsonNode entry : node) {
      paths.add(entry.path("path").asString(""));
    }
    return List.copyOf(paths);
  }

  public static TypeBlueprint decodeTypeBlueprint(String typeName, JsonNode data) {
    JsonNode args = data.path("args");
    return new TypeBlueprint(
      typeName,
      data.path("kind").asString(""),
      decodeMembers(args.path("fields")),
      decodeMembers(args.path("statics")));
  }

  private static List<TypeBlueprint.Member> decodeMembers(JsonNode node) {
    List<TypeBlueprint.Member> members = new ArrayList<>();
    for (JsonNode field : node) {
      members.add(new TypeBlueprint.Member(
        field.path("name").asString(""),
        JsonTypeRef.of(field.path("type")),
        field.path("kind").path("kind").asString("")));
    }
    return List.copyOf(members);
  }
}
