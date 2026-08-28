package com.intellij.plugins.haxe.profiler.tracy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One live tracy capture: connects OUT to the client (it listens on the
 * port the launcher assigned via TRACY_PORT), performs the handshake, asks
 * plot/thread names over the query channel as they first appear, and reads
 * until the client is drained. Shutdown is a two-step handshake and the
 * client NEVER closes the socket itself: it announces exit with a
 * Terminate item, and on receiving our Disconnect query it flushes what
 * remains, sends a FINAL Terminate and then waits for the server to close.
 * So: a Terminate with no disconnect requested yet triggers
 * {@link #requestDisconnect()}; one arriving after it means drained — the
 * read stops and closing our socket is what lets the client's process
 * exit. A capture can also end by the stream TEARING (the process killed,
 * or dead without the handshake): everything received before the tear is
 * kept. Queries are a handful of name lookups, far below the client's
 * query budget, so no flow-control bookkeeping is needed.
 */
public final class TracyLiveCapture {

  // ServerQuery wire values (TracyProtocol.hpp, v74)
  private static final int QUERY_STRING = 1;
  private static final int QUERY_THREAD_STRING = 2;
  private static final int QUERY_PLOT_NAME = 4;
  private static final int QUERY_DISCONNECT = 9;

  private static final int CONNECT_RETRY_MS = 100;

  private final Socket socket;
  private final OutputStream queries;
  private final AtomicBoolean disconnectSent = new AtomicBoolean();

  private TracyLiveCapture(Socket socket) throws IOException {
    this.socket = socket;
    this.queries = socket.getOutputStream();
  }

  /**
   * Connects with retries while {@code keepTrying} allows — the client's
   * listener comes up somewhere inside the process's startup. Null when the
   * window closed without a connection (the process died first).
   */
  public static TracyLiveCapture connect(int port, @NotNull BooleanSupplier keepTrying) throws IOException {
    while (keepTrying.getAsBoolean()) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        return new TracyLiveCapture(socket);
      }
      catch (IOException notListeningYet) {
        try {
          socket.close();
        }
        catch (IOException ignored) {
        }
        try {
          Thread.sleep(CONNECT_RETRY_MS);
        }
        catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    return null;
  }

  /** Handshakes and reads the whole session; returns when the client's stream ends. */
  @NotNull
  public TracySession capture() throws IOException {
    return capture(null);
  }

  /**
   * The streaming form: zones go to the sink as they close and stay out of
   * the returned session, so a minutes-long capture spools to disk instead
   * of filling the heap.
   */
  @NotNull
  public TracySession capture(TracyEventReader.@Nullable ZoneSink zoneSink) throws IOException {
    try (socket) {
      TracyWelcome welcome = TracyHandshake.perform(socket.getInputStream(), queries);
      TracyEventReader.Hooks hooks = new TracyEventReader.Hooks() {
        @Override
        public void plotSeen(long namePointer) {
          query(QUERY_PLOT_NAME, namePointer);
        }

        @Override
        public void threadSeen(int threadId) {
          query(QUERY_THREAD_STRING, threadId);
        }

        @Override
        public void memPoolSeen(long namePointer) {
          query(QUERY_STRING, namePointer);
        }

        @Override
        public boolean terminateSeen() {
          boolean drained = disconnectSent.get();
          requestDisconnect();
          return drained;
        }
      };
      TracyLz4Stream decompressed = new TracyLz4Stream(socket.getInputStream());
      // salvaging: a killed client tears the stream mid-item - the capture
      // keeps everything received up to that point (tracy's client exit
      // wedges before its shutdown handshake when system tracing is
      // active, so killing the process is a NORMAL way to end a capture)
      return TracyEventReader.readSalvaging(decompressed, welcome, hooks, zoneSink);
    }
  }

  /**
   * Acknowledges shutdown so the client flushes and closes; safe from any
   * thread and called at most once — later calls and send failures are
   * no-ops (the stream end is what actually finishes the capture).
   */
  public void requestDisconnect() {
    if (!disconnectSent.compareAndSet(false, true)) return;
    query(QUERY_DISCONNECT, 0);
  }

  /** ServerQueryPacket: u8 type, u64 ptr, u32 extra — little-endian, 13 bytes. */
  private void query(int type, long pointer) {
    byte[] packet = new byte[13];
    packet[0] = (byte)type;
    for (int i = 0; i < 8; i++) {
      packet[1 + i] = (byte)(pointer >> (8 * i) & 0xFF);
    }
    try {
      synchronized (queries) {
        queries.write(packet);
        queries.flush();
      }
    }
    catch (IOException gone) {
      // a torn connection surfaces as the reader's stream end; queries are best-effort
    }
  }
}
