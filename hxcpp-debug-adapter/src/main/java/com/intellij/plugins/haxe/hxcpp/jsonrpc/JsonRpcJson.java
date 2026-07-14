package com.intellij.plugins.haxe.hxcpp.jsonrpc;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes/decodes hxcpp-debug-server jsonrpc messages.
 *
 * Decoding discriminates on the envelope: a message carrying an {@code id}
 * is the response to one of our requests; one carrying only a {@code method}
 * is a notification. The server never sends its own requests, so an id
 * combined with a method is rejected as corruption rather than guessed at.
 */
public final class JsonRpcJson {
  private static final ObjectMapper MAPPER = JsonMapper.builder()
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build();

  private JsonRpcJson() {
  }

  public static String encode(JsonRpcRequest request) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("id", request.id());
    root.put("method", request.method());
    root.set("params", request.params() == null
                       ? MAPPER.createObjectNode()
                       : MAPPER.valueToTree(request.params()));
    return MAPPER.writeValueAsString(root);
  }

  public static JsonRpcServerMessage decode(String json) {
    JsonNode root = MAPPER.readTree(json);
    boolean hasId = root.hasNonNull("id");
    boolean hasMethod = root.hasNonNull("method");
    if (hasId && !hasMethod) {
      JsonRpcError error = null;
      JsonNode errorNode = root.get("error");
      if (errorNode != null && !errorNode.isNull()) {
        error = new JsonRpcError(errorNode.path("code").asInt(0),
                                 errorNode.path("message").asString(""));
      }
      return new JsonRpcResponse(root.get("id").asInt(), root.get("result"), error);
    }
    if (hasMethod && !hasId) {
      return new JsonRpcNotification(root.get("method").asString(), root.get("params"));
    }
    throw new IllegalArgumentException("Not a jsonrpc response or notification: " + json);
  }
}
