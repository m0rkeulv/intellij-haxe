package com.intellij.plugins.haxe.profiler.hxt;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an HXTS v1 sample session — the counterpart of
 * {@link HxtSessionTranslator}'s reader, used by the IDE-side transcoders
 * (flash telemetry, V8 segments) whose sources are not v1 themselves. One
 * microsecond per tick; the header goes out with the first frame and every
 * frame record is flushed, so a live view can follow the growing file.
 * Frame windows must arrive in order: each record's samples tile the
 * stretch from the previous frame's stamp to its own.
 */
public final class HxtSessionWriter {

  /** One tick = one microsecond: sample weights are exact clock time. */
  public static final int TICK_HZ = 1_000_000;
  /** The record's boundary is a collection flush, not a display frame — the reader emits no frame event for it. */
  public static final int FLAG_SEGMENT_WINDOW = 1;
  /** The record carries no heap reading — the reader emits no memory sample for it. */
  public static final int FLAG_NO_HEAP_READING = 2;

  private final OutputStream out;
  private final String target;
  /** The v1 name table across the whole session; per-record additions collect in {@code newNames}. */
  private final Map<String, Integer> nameIndexes = new HashMap<>();
  private final List<String> newNames = new ArrayList<>();
  private long bytesWritten;
  private boolean headerWritten;

  /**
   * A root-first stack and the microseconds it accounts for. A name entry
   * is {@code symbol} or {@code symbol(path/File.hx:123)} — the reader
   * parses a position suffix back into file and line.
   */
  public record WeightedStack(@NotNull List<String> rootFirstStack, long weightUs) {}

  public HxtSessionWriter(@NotNull OutputStream out, @NotNull String target) {
    this.out = out;
    this.target = target;
  }

  public long bytesWritten() {
    return bytesWritten;
  }

  /** A display-frame window with a heap reading; see {@link #writeFrame(double, long, long, long, List, int)}. */
  public void writeFrame(double stampSeconds, long gcUs, long usedBytes, long reservedBytes,
                         @NotNull List<WeightedStack> samples) throws IOException {
    writeFrame(stampSeconds, gcUs, usedBytes, reservedBytes, samples, 0);
  }

  /** One frame window ending at {@code stampSeconds}; {@code samples} tile it edge to edge in order. */
  public void writeFrame(double stampSeconds, long gcUs, long usedBytes, long reservedBytes,
                         @NotNull List<WeightedStack> samples, int flags) throws IOException {
    if (!headerWritten) {
      headerWritten = true;
      writeHeader();
    }
    newNames.clear();
    List<Integer> sampleInts = new ArrayList<>();
    for (WeightedStack sample : samples) {
      sampleInts.add(sample.rootFirstStack().size());
      for (String frame : sample.rootFirstStack()) {
        sampleInts.add(nameIndex(frame));
      }
      sampleInts.add((int)Math.min(sample.weightUs(), Integer.MAX_VALUE));
    }

    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    writeDouble(payload, stampSeconds);
    writeInt(payload, (int)Math.min(gcUs, Integer.MAX_VALUE));
    writeInt(payload, 0); // gcOverheadUs
    writeInt(payload, (int)Math.min(usedBytes, Integer.MAX_VALUE));
    writeInt(payload, (int)Math.min(reservedBytes, Integer.MAX_VALUE));
    writeInt(payload, newNames.size());
    for (String name : newNames) {
      writeName(payload, name);
    }
    writeInt(payload, sampleInts.size());
    for (int value : sampleInts) {
      writeInt(payload, value);
    }
    if (flags != 0) {
      // the flags byte sits after the optional alloc counters, so both ship
      writeInt(payload, 0); // allocatedBytes
      writeInt(payload, 0); // freedBytes
      payload.write(flags);
    }

    ByteArrayOutputStream record = new ByteArrayOutputStream();
    record.write(HxtSessionTranslator.FRAME_RECORD);
    writeInt(record, payload.size());
    payload.writeTo(record);
    record.writeTo(out);
    out.flush(); // the live view re-reads the file while it grows
    bytesWritten += record.size();
  }

  private int nameIndex(String name) {
    Integer known = nameIndexes.get(name);
    if (known != null) return known;
    newNames.add(name);
    int index = nameIndexes.size() + 1; // the v1 table is 1-based
    nameIndexes.put(name, index);
    return index;
  }

  private void writeHeader() throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    header.writeBytes(HxtSessionTranslator.MAGIC);
    header.write(1);
    header.write(0); // u16 version 1
    writeInt(header, TICK_HZ);
    writeDouble(header, 0.0); // transcoded sources rebase their clock to the session start
    writeName(header, target);
    header.writeTo(out);
    bytesWritten += header.size();
  }

  private static void writeName(ByteArrayOutputStream out, String name) {
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    out.write(utf8.length & 0xFF);
    out.write(utf8.length >> 8 & 0xFF);
    out.writeBytes(utf8);
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    for (int i = 0; i < 4; i++) {
      out.write(value >> 8 * i & 0xFF);
    }
  }

  private static void writeDouble(ByteArrayOutputStream out, double value) {
    long bits = Double.doubleToLongBits(value);
    for (int i = 0; i < 8; i++) {
      out.write((int)(bits >> 8 * i & 0xFF));
    }
  }
}
