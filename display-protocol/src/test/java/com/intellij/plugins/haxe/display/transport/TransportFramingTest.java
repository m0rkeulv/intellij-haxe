package com.intellij.plugins.haxe.display.transport;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Display protocol: transport framing")
public class TransportFramingTest {

  private static final char LOG_MARK = 1;
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
  @DisplayName("request is newline separated args with a null terminator")
  public void requestIsNewlineSeparatedArgsWithANullTerminator() throws Exception {
    Future<byte[]> received = fakeServer("{}\n".getBytes(StandardCharsets.UTF_8));

    HaxeDisplayTransport.request("127.0.0.1", server.getLocalPort(),
                                 List.of("--cwd", "/work", "--display", "{\"a\":1}"), 5_000);

    String request = new String(received.get(), StandardCharsets.UTF_8);
    assertEquals("--cwd\n/work\n--display\n{\"a\":1}\n", request);
  }

  @Test
  @Timeout(10)
  @DisplayName("response lines classify into payload, logs and error marker")
  public void responseLinesClassifyIntoPayloadLogsAndErrorMarker() throws Exception {
    // one log line whose embedded newline arrives as a second 0x01 byte,
    // the JSON payload, and the fatal-error marker line
    String reply = LOG_MARK + "a log line" + LOG_MARK + "with continuation\n"
                   + "{\"jsonrpc\":\"2.0\"}\n"
                   + ERROR_MARK + "\n";
    fakeServer(reply.getBytes(StandardCharsets.UTF_8));

    DisplayResponse response =
      HaxeDisplayTransport.request("127.0.0.1", server.getLocalPort(), List.of("Main"), 5_000);

    assertEquals("{\"jsonrpc\":\"2.0\"}", response.payload());
    assertEquals(List.of("a log line\nwith continuation"), response.logs());
    assertTrue(response.hasError());
  }

  @Test
  @Timeout(10)
  @DisplayName("plain compiler error output becomes payload with the error flag")
  public void plainCompilerErrorOutputBecomesPayloadWithTheErrorFlag() throws Exception {
    String reply = "Main.hx:7: characters 3-15 : Unknown identifier : unknownIdent\n" + ERROR_MARK + "\n";
    fakeServer(reply.getBytes(StandardCharsets.UTF_8));

    DisplayResponse response =
      HaxeDisplayTransport.request("127.0.0.1", server.getLocalPort(), List.of("Main"), 5_000);

    assertTrue(response.hasError());
    assertEquals("Main.hx:7: characters 3-15 : Unknown identifier : unknownIdent", response.payload());
  }

  /** Accepts one connection, captures the request bytes up to the null terminator, replies and closes. */
  private Future<byte[]> fakeServer(byte[] reply) {
    return executor.submit(() -> {
      try (Socket socket = server.accept()) {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
          received.write(b);
        }
        OutputStream out = socket.getOutputStream();
        out.write(reply);
        out.flush();
        return received.toByteArray();
      }
    });
  }
}
