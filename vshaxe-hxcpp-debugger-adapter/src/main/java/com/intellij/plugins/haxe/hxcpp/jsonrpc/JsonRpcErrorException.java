package com.intellij.plugins.haxe.hxcpp.jsonrpc;

import java.io.IOException;

/** Thrown by {@link JsonRpcClient#call} when the server answers a request with an error. */
public class JsonRpcErrorException extends IOException {
  private final String method;
  private final JsonRpcError error;

  public JsonRpcErrorException(String method, JsonRpcError error) {
    super("'" + method + "' failed: " + error.message() + " (code " + error.code() + ")");
    this.method = method;
    this.error = error;
  }

  public String getMethod() {
    return method;
  }

  public JsonRpcError getError() {
    return error;
  }
}
