package com.intellij.plugins.haxe.runner.debugger.eval;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives EvalConnection against an in-process FAKE VM on piped streams: the
 * fake reads 2-byte-LE-framed requests and answers with 4-byte-LE-framed
 * responses, mirroring the real asymmetric framing end to end.
 */
public class EvalConnectionTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EvalConnection connection;
  private Thread fakeVm;

  /**
   * Starts a fake VM whose reply is computed from each decoded request. A
   * newline-separated reply means several messages, each framed alone (raw
   * newlines never occur inside single-line JSON).
   */
  private void startFake(Function<JsonNode, String> replyFor) throws IOException {
    PipedInputStream vmSees = new PipedInputStream(1 << 16);
    PipedOutputStream toVm = new PipedOutputStream(vmSees);
    PipedInputStream weSee = new PipedInputStream(1 << 16);
    PipedOutputStream fromVm = new PipedOutputStream(weSee);

    fakeVm = new Thread(() -> {
      try {
        while (true) {
          int low = vmSees.read();
          if (low < 0) {
            return;
          }
          int high = vmSees.read();
          byte[] body = vmSees.readNBytes(low | (high << 8));
          JsonNode request = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
          String reply = replyFor.apply(request);
          if (reply != null) {
            for (String part : reply.split("\n")) {
              byte[] replyBody = part.getBytes(StandardCharsets.UTF_8);
              fromVm.write(new byte[]{(byte)replyBody.length, (byte)(replyBody.length >>> 8),
                                      (byte)(replyBody.length >>> 16), (byte)(replyBody.length >>> 24)});
              fromVm.write(replyBody);
            }
            fromVm.flush();
          }
        }
      } catch (IOException ignored) {
        // pipe closed: test over
      }
    }, "fake-eval-vm");
    fakeVm.setDaemon(true);
    fakeVm.start();

    connection = new EvalConnection(weSee, toVm);
  }

  @After
  public void tearDown() {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  public void correlatesResponsesByIdAndUnwrapsResult() throws Exception {
    startFake(request -> {
      assertEquals("strict envelope", "2.0", request.path("jsonrpc").asString());
      return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asInt()
             + ",\"result\":[{\"id\":0,\"name\":\"Thread 0\"}]}";
    });
    connection.start();
    JsonNode result = connection.request("getThreads", null, 5000);
    assertEquals("Thread 0", result.get(0).path("name").asString());
  }

  @Test
  public void errorResponsesSurfaceAsProtocolExceptions() throws Exception {
    startFake(request -> "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asInt()
                         + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
    connection.start();
    EvalProtocolException error = assertThrows(EvalProtocolException.class,
                                               () -> connection.request("bogus", null, 5000));
    assertEquals(-32601, error.getCode());
    assertTrue(error.getMessage().contains("Method not found"));
  }

  @Test
  public void idLessMessagesRouteToTheEventListener() throws Exception {
    startFake(request -> {
      // reply to the request, then push an unrelated notification
      String id = Integer.toString(request.path("id").asInt());
      return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":null}"
             + "\n"
             + "{\"jsonrpc\":\"2.0\",\"method\":\"breakpointStop\",\"params\":{\"threadId\":0}}";
    });
    BlockingQueue<String> events = new LinkedBlockingQueue<>();
    connection.setEventListener((method, params) -> events.add(method + ":" + params.path("threadId").asInt(-1)));
    connection.start();
    connection.request("continue", null, 5000);
    assertEquals("breakpointStop:0", events.poll(5, TimeUnit.SECONDS));
  }

  @Test
  public void transportDeathFailsPendingRequests() throws Exception {
    startFake(request -> null); // never answers
    connection.start();
    Thread killer = new Thread(() -> {
      try {
        Thread.sleep(300);
      } catch (InterruptedException ignored) {
      }
      connection.close();
    });
    killer.setDaemon(true);
    killer.start();
    assertThrows(IOException.class, () -> connection.request("getThreads", null, 10_000));
  }
}
