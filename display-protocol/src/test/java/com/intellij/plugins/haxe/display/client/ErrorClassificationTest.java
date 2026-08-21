package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how the client classifies a non-JSON payload: with the fatal-error
 * marker it is the compiler's own error report (a failed compile), without
 * it a genuinely malformed response.
 */
@DisplayName("Display protocol: error classification")
public class ErrorClassificationTest {

  private static final char ERROR_MARK = 2;

  private ServerSocket server;
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws Exception {
    server = new ServerSocket(0);
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.close();
    executor.shutdownNow();
  }

  @Test
  @Timeout(10)
  @DisplayName("compiler error payload reports the request as failed")
  public void compilerErrorPayloadReportsTheRequestAsFailed() {
    String reply = "Main.hx:7: characters 3-15 : Unknown identifier : unknownIdent\n" + ERROR_MARK + "\n";
    fakeServer(reply.getBytes(StandardCharsets.UTF_8));
    HaxeDisplayClient client = new HaxeDisplayClient("127.0.0.1", server.getLocalPort());

    DisplayRequestException e =
      assertThrows(DisplayRequestException.class, () -> client.projectDiagnostics(List.of("Main")));

    String message = e.getMessage();
    assertTrue(message.startsWith("Display request 'display/diagnostics' failed:"), message);
    assertTrue(message.contains("Unknown identifier : unknownIdent"), message);
    assertFalse(message.contains("Malformed"), message);
  }

  @Test
  @Timeout(10)
  @DisplayName("unparseable payload without the error flag stays malformed")
  public void unparseablePayloadWithoutTheErrorFlagStaysMalformed() {
    fakeServer("not json at all\n".getBytes(StandardCharsets.UTF_8));
    HaxeDisplayClient client = new HaxeDisplayClient("127.0.0.1", server.getLocalPort());

    DisplayRequestException e =
      assertThrows(DisplayRequestException.class, () -> client.projectDiagnostics(List.of("Main")));

    assertTrue(e.getMessage().startsWith("Malformed display response:"), e.getMessage());
  }

  /** Accepts one connection, drains the request up to the null terminator, replies and closes. */
  private void fakeServer(byte[] reply) {
    executor.submit(() -> {
      try (Socket socket = server.accept()) {
        InputStream in = socket.getInputStream();
        while (in.read() > 0) {
          // drain until the null terminator
        }
        OutputStream out = socket.getOutputStream();
        out.write(reply);
        out.flush();
        return null;
      }
    });
  }
}
