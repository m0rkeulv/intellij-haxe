package com.intellij.plugins.haxe.runner.debugger.javascript;

import com.intellij.plugins.haxe.runner.debugger.dap.client.DapEndpoint;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Response;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.InitializeRequest;

import java.io.IOException;

/** The js-debug handshake step every session shares: the parent, each child the mux attaches, the test host's page. */
public final class JsDebugHandshake {

  /** The client name the IDE announces; js-debug keys some behaviour on it. */
  public static final String CLIENT_NAME = "IntelliJ Haxe";

  private JsDebugHandshake() {
  }

  /** The initialize request for the adapter id, with the IDE's client name; fails when the adapter rejects it. */
  public static void initialize(DapEndpoint endpoint, String adapterId, long timeoutMillis) throws IOException, InterruptedException {
    InitializeRequest initialize = InitializeRequest.standard(adapterId, true);
    initialize.getArguments().setClientName(CLIENT_NAME);
    Response response = endpoint.sendRequest(initialize, timeoutMillis);
    if (!response.isSuccess()) {
      throw new IOException("the adapter rejected initialize: " + response.getMessage());
    }
  }
}
