package com.intellij.plugins.haxe.hxcpp.jsonrpc;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * A synchronous-with-timeout jsonrpc client on top of {@link JsonRpcConnection}.
 *
 * A background reader thread demultiplexes incoming messages: responses are
 * matched to their request by id, notifications go to a queue that callers
 * drain with {@link #pollNotification}. Owns the client-side id counter.
 */
public class JsonRpcClient implements Closeable {
  private final JsonRpcConnection connection;
  private final Thread readerThread;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final ConcurrentMap<Integer, BlockingQueue<JsonRpcResponse>> pendingResponses = new ConcurrentHashMap<>();
  private final BlockingQueue<JsonRpcNotification> notifications = new LinkedBlockingQueue<>();
  private volatile boolean closed = false;

  public JsonRpcClient(JsonRpcConnection connection) {
    this.connection = connection;
    readerThread = new Thread(this::readLoop, "hxcpp-jsonrpc-reader");
    readerThread.setDaemon(true);
    readerThread.start();
  }

  /**
   * Assigns the next id to a request for {@code method}, sends it, and blocks
   * until the matching response arrives or the timeout elapses.
   */
  public JsonRpcResponse sendRequest(String method, Object params, long timeoutMillis)
    throws IOException, InterruptedException {
    int id = nextId.getAndIncrement();
    BlockingQueue<JsonRpcResponse> pending = new ArrayBlockingQueue<>(1);
    pendingResponses.put(id, pending);
    try {
      connection.send(new JsonRpcRequest(id, method, params));
      JsonRpcResponse response = pending.poll(timeoutMillis, TimeUnit.MILLISECONDS);
      if (response == null) {
        throw new IOException("Timed out waiting for response to '" + method + "' (id " + id + ")");
      }
      return response;
    } finally {
      pendingResponses.remove(id);
    }
  }

  /**
   * Like {@link #sendRequest} but unwraps the response: returns the result
   * tree on success, throws {@link JsonRpcErrorException} on a server error.
   */
  public JsonNode call(String method, Object params, long timeoutMillis)
    throws IOException, InterruptedException {
    JsonRpcResponse response = sendRequest(method, params, timeoutMillis);
    if (response.isError()) {
      throw new JsonRpcErrorException(method, response.error());
    }
    return response.result();
  }

  /** Returns the next notification, waiting up to the timeout; null when none arrived. */
  public JsonRpcNotification pollNotification(long timeoutMillis) throws InterruptedException {
    return notifications.poll(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  private void readLoop() {
    try {
      while (true) {
        JsonRpcServerMessage message = connection.receive();
        if (message == null) {
          return;
        }
        if (message instanceof JsonRpcResponse response) {
          BlockingQueue<JsonRpcResponse> pending = pendingResponses.get(response.id());
          if (pending != null) {
            pending.offer(response);
          }
        }
        else if (message instanceof JsonRpcNotification notification) {
          notifications.offer(notification);
        }
      }
    } catch (IOException | RuntimeException e) {
      // A framing/decode failure must not silently kill the demultiplexer —
      // after this thread dies every later request times out with no hint why.
      // Only a deliberate close() is an expected way for the read to end.
      if (!closed) {
        System.err.println("JsonRpcClient reader died: " + e);
        e.printStackTrace();
      }
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    connection.close();
  }
}
