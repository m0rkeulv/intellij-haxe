package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerEvent;
import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.model.ProfilerMemorySample;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental reader for a GROWING v1 session file: {@link #poll} consumes
 * only the records appended since the last poll (a trailing partial record
 * waits for the next one) and {@link #snapshot} serves the accumulated
 * capture — a live view's per-tick cost then follows the NEW data, not the
 * session length. Single consumer: poll and snapshot from one thread at a
 * time; the snapshot's lists are copies, safe to hand across threads.
 */
public final class HxtLiveSession {

  private final Path file;
  private final int tickHz;
  private final String target;
  /** The next unread byte; only COMPLETE records advance it. */
  private long offset;
  private double windowStart;

  private final List<String> names = new ArrayList<>();
  private final Map<Integer, StackFrame> frames = new HashMap<>();
  private final List<StackSample> samples = new ArrayList<>();
  private final List<ProfilerEvent> events = new ArrayList<>();
  private final List<ProfilerMemorySample> memory = new ArrayList<>();

  private HxtLiveSession(Path file, int tickHz, double startStamp, String target, long headerBytes) {
    this.file = file;
    this.tickHz = tickHz;
    this.target = target;
    this.windowStart = startStamp;
    this.offset = headerBytes;
    names.add(""); // the table is 1-indexed
  }

  /** Opens a file whose v1 header is already on disk; records start streaming in via {@link #poll}. */
  @NotNull
  public static HxtLiveSession open(@NotNull Path file) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      DataInputStream data = new DataInputStream(in);
      byte[] magic = data.readNBytes(HxtSessionTranslator.MAGIC.length);
      if (magic.length < 4 || magic[0] != 'H' || magic[1] != 'X' || magic[2] != 'T' || magic[3] != 'S') {
        throw new ProfilerFormatException("not an HXTS telemetry session");
      }
      int version = data.readUnsignedByte() | data.readUnsignedByte() << 8;
      if (version != 1) {
        throw new ProfilerFormatException("live reading needs a v1 (sampled) session, not version " + version);
      }
      int tickHz = 0;
      for (int i = 0; i < 4; i++) tickHz |= data.readUnsignedByte() << (8 * i);
      if (tickHz <= 0) throw new ProfilerFormatException("invalid tick rate: " + tickHz);
      long stampBits = 0;
      for (int i = 0; i < 8; i++) stampBits |= (long)data.readUnsignedByte() << (8 * i);
      int targetLength = data.readUnsignedByte() | data.readUnsignedByte() << 8;
      String target = new String(data.readNBytes(targetLength), StandardCharsets.UTF_8);
      long headerBytes = 4 + 2 + 4 + 8 + 2 + target.getBytes(StandardCharsets.UTF_8).length;
      return new HxtLiveSession(file, tickHz, Double.longBitsToDouble(stampBits), target, headerBytes);
    }
  }

  /** Consumes the complete records appended since the last poll; true when any landed. */
  public boolean poll() throws IOException {
    boolean grew = false;
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      while (size - offset >= 5) {
        ByteBuffer head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, head, offset);
        int type = head.get(0) & 0xFF;
        int length = head.getInt(1);
        if (length < 0 || size - offset - 5 < length) break; // partial (or corrupt) tail - next poll
        ByteBuffer payload = ByteBuffer.allocate(length);
        readFully(channel, payload, offset + 5);
        offset += 5 + length;
        if (type != HxtSessionTranslator.FRAME_RECORD) continue;
        try {
          windowStart = HxtSessionTranslator.readFrame(payload.array(), tickHz, windowStart,
                                                       names, frames, samples, events, memory);
          grew = true;
        }
        catch (EOFException corruptRecord) {
          // a record shorter than its fields promises - skip it, keep streaming
        }
      }
    }
    return grew;
  }

  /** The capture accumulated so far; list copies, safe to hand to another thread. */
  @NotNull
  public ProfilerSnapshot snapshot() {
    List<ProfilerThread> threads = List.of(new ProfilerThread(0, "Main"));
    return new ProfilerSnapshot(target, 1, tickHz, threads, List.copyOf(samples),
                                List.copyOf(events), List.copyOf(memory));
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
    long at = position;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, at);
      if (read < 0) throw new EOFException("session file shrank under the live reader");
      at += read;
    }
  }
}
