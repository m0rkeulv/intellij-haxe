package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.*;
import com.intellij.plugins.haxe.display.protocol.server.HaxeServerContext;
import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures are captured responses of a live haxe 4.3.7 server (trimmed).
 * Ranges on the wire are 0-BASED — the assertions pin that down.
 */
@DisplayName("Display protocol: json decoding")
public class DisplayJsonTest {

  @Test
  @DisplayName("request envelope carries jsonrpc id method and params")
  public void requestEnvelopeCarriesJsonrpcIdMethodAndParams() {
    String request = DisplayJson.encodeRequest("display/hover", java.util.Map.of("offset", 42));
    assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"display/hover\",\"params\":{\"offset\":42}}", request);
  }

  @Test
  @DisplayName("json rpc error unwraps into an exception")
  public void jsonRpcErrorUnwrapsIntoAnException() {
    String payload = """
      {"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"not an object"}}""";
    DisplayRequestException e = assertThrows(DisplayRequestException.class, () -> DisplayJson.unwrap(payload));
    assertTrue(e.getMessage().contains("not an object"));
  }

  @Test
  @DisplayName("diagnostics decode with zero based ranges and typed args")
  public void diagnosticsDecodeWithZeroBasedRangesAndTypedArgs() throws Exception {
    // captured: unused import on file line 1, unresolved identifier on file line 7
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":[{"file":"C:\\\\work\\\\Main.hx","diagnostics":[
        {"kind":1,"severity":1,"range":{"start":{"line":6,"character":2},"end":{"line":6,"character":14}},
         "args":[{"kind":0,"name":"haxe.ds.StringMap"}],"relatedInformation":[]},
        {"kind":2,"severity":2,"range":{"start":{"line":6,"character":2},"end":{"line":6,"character":14}},
         "args":"This code has no effect","relatedInformation":[]},
        {"kind":0,"severity":2,"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":25}},
         "args":[],"relatedInformation":[]}]}],"timestamp":1785367994.26}}""";

    List<FileDiagnostics> files = DisplayJson.decodeDiagnostics(DisplayJson.unwrap(payload));

    assertEquals(1, files.size());
    FileDiagnostics file = files.get(0);
    assertEquals("C:\\work\\Main.hx", file.file());
    assertEquals(3, file.diagnostics().size());

    Diagnostic unresolved = file.diagnostics().get(0);
    assertEquals(DiagnosticKind.UNRESOLVED_IDENTIFIER, unresolved.kind());
    assertEquals(DiagnosticSeverity.ERROR, unresolved.severity());
    assertEquals(new Range(new Position(6, 2), new Position(6, 14)), unresolved.range());
    assertEquals(List.of(new Diagnostic.IdentifierSuggestion(0, "haxe.ds.StringMap")),
                 unresolved.suggestionArgs());
    assertTrue(unresolved.suggestionArgs().get(0).isImportCandidate());

    Diagnostic warning = file.diagnostics().get(1);
    assertEquals(DiagnosticKind.COMPILER_ERROR, warning.kind());
    assertEquals("This code has no effect", warning.messageArg());

    assertEquals(DiagnosticKind.UNUSED_IMPORT, file.diagnostics().get(2).kind());
  }

  @Test
  @DisplayName("hover decodes item kind and json type")
  public void hoverDecodesItemKindAndJsonType() throws Exception {
    // captured hover over a local String variable (trimmed)
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":{"documentation":null,
        "range":{"start":{"line":5,"character":8},"end":{"line":5,"character":16}},
        "item":{"kind":"Local","args":{"name":"greeting"},
          "type":{"kind":"TInst","args":{"path":{"pack":[],"moduleName":"String","typeName":"String"},"params":[]}}}},
        "timestamp":1785368029.61}}""";

    HoverInfo hover = DisplayJson.decodeHover(DisplayJson.unwrap(payload));

    assertNotNull(hover);
    assertEquals("Local", hover.itemKind());
    assertEquals(new Range(new Position(5, 8), new Position(5, 16)), hover.range());
    assertEquals("String", hover.type().dotPath());
    assertEquals("String", hover.type().presentable());
  }

  @Test
  @DisplayName("hover over nothing decodes to null")
  public void hoverOverNothingDecodesToNull() throws Exception {
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":null,"timestamp":1785368029.61}}""";
    assertNull(DisplayJson.decodeHover(DisplayJson.unwrap(payload)));
  }

  @Test
  @DisplayName("initialize decodes versions and the method list")
  public void initializeDecodesVersionsAndTheMethodList() throws Exception {
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":{
        "methods":["display/definition","display/diagnostics","server/invalidate"],
        "haxeVersion":{"major":4,"minor":3,"patch":7,"pre":null,"build":null},
        "protocolVersion":{"major":0,"minor":5,"patch":0}},"timestamp":1785368042.45}}""";

    InitializeResult result = DisplayJson.decodeInitialize(DisplayJson.unwrap(payload));

    assertEquals("4.3.7", result.haxeVersion().toString());
    assertEquals(0, result.protocolVersion().major());
    assertEquals(5, result.protocolVersion().minor());
    assertTrue(result.supports("display/diagnostics"));
    assertFalse(result.supports("display/hover"));
  }

  @Test
  @DisplayName("contexts decode signature platform and defines")
  public void contextsDecodeSignaturePlatformAndDefines() throws Exception {
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":[
        {"index":0,"desc":"after_init_macros","platform":"js",
         "classPaths":["./","C:\\\\haxe\\\\std/"],"signature":"c5da38c653f9",
         "defines":[{"key":"js","value":"1"},{"key":"utf16","value":"1"}]}],"timestamp":1.0}}""";

    List<HaxeServerContext> contexts = DisplayJson.decodeContexts(DisplayJson.unwrap(payload));

    assertEquals(1, contexts.size());
    HaxeServerContext context = contexts.get(0);
    assertEquals("after_init_macros", context.desc());
    assertEquals("js", context.platform());
    assertEquals("c5da38c653f9", context.signature());
    assertEquals("1", context.defines().get("js"));
  }

  @Test
  @DisplayName("type blueprint decodes fields and statics with member lookup")
  public void typeBlueprintDecodesFieldsAndStaticsWithMemberLookup() throws Exception {
    // captured server/type of a class with one field, one method, one static (trimmed)
    String payload = """
      {"jsonrpc":"2.0","id":1,"result":{"result":{"kind":"class","args":{
        "fields":[
          {"name":"label","type":{"kind":"TInst","args":{"path":{"pack":[],"moduleName":"String","typeName":"String"},"params":[]}}},
          {"name":"shout","type":{"kind":"TFun","args":{"args":[],
            "ret":{"kind":"TInst","args":{"path":{"pack":[],"moduleName":"String","typeName":"String"},"params":[]}}}}}],
        "statics":[
          {"name":"main","type":{"kind":"TFun","args":{"args":[],
            "ret":{"kind":"TAbstract","args":{"path":{"pack":[],"moduleName":"StdTypes","typeName":"Void"},"params":[]}}}}}]}},
        "timestamp":1.0}}""";

    JsonNode data = DisplayJson.unwrap(payload);
    TypeBlueprint blueprint = DisplayJson.decodeTypeBlueprint("Clean", data);

    assertEquals("class", blueprint.kind());
    assertEquals(2, blueprint.fields().size());
    assertEquals(1, blueprint.statics().size());

    TypeBlueprint.Member label = blueprint.findMember("label");
    assertNotNull(label);
    assertEquals("String", label.type().dotPath());

    TypeBlueprint.Member shout = blueprint.findMember("shout");
    assertNotNull(shout);
    assertTrue(shout.type().isFunction());
    assertEquals("() -> String", shout.type().presentable());

    TypeBlueprint.Member main = blueprint.findMember("main");
    assertNotNull(main);
    assertEquals("() -> Void", main.type().presentable());

    assertNull(blueprint.findMember("nope"));
  }
}
