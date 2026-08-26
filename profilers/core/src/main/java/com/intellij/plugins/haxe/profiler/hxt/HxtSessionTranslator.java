package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerEvent;
import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an HXTS telemetry session — the format our injected hxcpp collector
 * streams over the socket and the IDE receiver persists verbatim, so wire
 * and disk are the same bytes. Little-endian throughout.
 *
 * <pre>
 * header:  'H','X','T','S', u16 version, u32 tickHz,
 *          f64 startStamp (seconds), u16 targetLen + utf8 target
 * record:  u8 type, u32 payloadLength, payload — unknown types are skipped
 * FRAME (type 1) payload:
 *          f64 stamp (seconds, taken at stash = the window's END),
 *          i32 gcTimeUs, i32 gcOverheadUs, i32 usedBytes, i32 reservedBytes,
 *          i32 nameCount, nameCount * (u16 len + utf8)   — appended to the
 *              session's accumulated name table (1-indexed, index 0 unused),
 *          i32 sampleIntCount, that many i32s: groups of
 *              [depth, depth * nameIndex (root-first), deltaTicks]
 * </pre>
 *
 * Sample times are reconstructed inside each frame's window: the previous
 * frame's stamp plus the cumulative tick time. A truncated final record
 * (the app died mid-write) ends the session cleanly with what was read.
 */
public final class HxtSessionTranslator {

  static final byte[] MAGIC = {'H', 'X', 'T', 'S'};
  static final int FRAME_RECORD = 1;

  private HxtSessionTranslator() {
  }

  @NotNull
  public static ProfilerSnapshot translate(@NotNull InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    readMagic(data);
    int version = readU16(data);
    int tickHz = readInt(data);
    if (tickHz <= 0) throw new ProfilerFormatException("invalid tick rate: " + tickHz);
    double startStamp = readDouble(data);
    String target = readString(data, readU16(data));

    List<String> names = new ArrayList<>();
    names.add(""); // the table is 1-indexed
    Map<Integer, StackFrame> frames = new HashMap<>();
    List<StackSample> samples = new ArrayList<>();
    List<ProfilerEvent> events = new ArrayList<>();
    double windowStart = startStamp;

    while (true) {
      int type;
      try {
        type = data.readUnsignedByte();
      }
      catch (EOFException end) {
        break;
      }
      byte[] payload;
      try {
        payload = data.readNBytes(readInt(data));
      }
      catch (EOFException truncated) {
        break; // the app died mid-record - keep what is complete
      }
      if (type == FRAME_RECORD) {
        try {
          windowStart = readFrame(payload, tickHz, windowStart, names, frames, samples, events);
        }
        catch (EOFException truncated) {
          break;
        }
      }
    }

    List<ProfilerThread> threads = List.of(new ProfilerThread(0, "Main"));
    return new ProfilerSnapshot(target, version, tickHz, threads, List.copyOf(samples), List.copyOf(events));
  }

  /** Reads one frame payload; returns the next window's start (this frame's stamp). */
  private static double readFrame(byte[] payload, int tickHz, double windowStart,
                                  List<String> names, Map<Integer, StackFrame> frames,
                                  List<StackSample> samples, List<ProfilerEvent> events) throws IOException {
    DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
    double stamp = readDouble(data);
    int gcTimeUs = readInt(data);
    readInt(data); // gcOverheadUs - not surfaced yet
    readInt(data); // usedBytes - future memory chart source
    readInt(data); // reservedBytes

    int nameCount = readInt(data);
    for (int i = 0; i < nameCount; i++) {
      names.add(readString(data, readU16(data)));
    }

    int sampleInts = readInt(data);
    double tickSeconds = 1.0 / tickHz;
    long cumulativeTicks = 0;
    int consumed = 0;
    while (consumed < sampleInts) {
      int depth = readInt(data);
      if (depth < 0 || depth > sampleInts - consumed - 2) {
        throw new ProfilerFormatException("corrupt sample group (depth " + depth + ")");
      }
      List<StackFrame> stack = new ArrayList<>(depth);
      for (int i = 0; i < depth; i++) {
        stack.add(frameFor(readInt(data), names, frames));
      }
      int deltaTicks = readInt(data);
      consumed += depth + 2;
      cumulativeTicks += Math.max(deltaTicks, 1);
      double time = Math.min(windowStart + cumulativeTicks * tickSeconds, stamp);
      samples.add(new StackSample(time, 0, List.copyOf(stack), Math.max(deltaTicks, 1), false));
    }

    events.add(new ProfilerEvent(stamp, 0, ProfilerEvent.FRAME_CODE, ""));
    if (gcTimeUs > 0) {
      events.add(new ProfilerEvent(stamp, 0, ProfilerEvent.GC_TIME_CODE, Integer.toString(gcTimeUs)));
    }
    return stamp;
  }

  private static StackFrame frameFor(int nameIndex, List<String> names, Map<Integer, StackFrame> frames) throws IOException {
    if (nameIndex <= 0 || nameIndex >= names.size()) {
      throw new ProfilerFormatException("sample references unknown name index " + nameIndex);
    }
    return frames.computeIfAbsent(nameIndex, index -> new StackFrame(names.get(index), null, StackFrame.NO_LINE));
  }

  private static void readMagic(DataInputStream data) throws IOException {
    byte[] magic = data.readNBytes(MAGIC.length);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new ProfilerFormatException("not an HXTS telemetry session");
    }
  }

  private static int readU16(DataInputStream data) throws IOException {
    int low = data.readUnsignedByte();
    return low | data.readUnsignedByte() << 8;
  }

  private static int readInt(DataInputStream data) throws IOException {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value |= data.readUnsignedByte() << (8 * i);
    }
    return value;
  }

  private static double readDouble(DataInputStream data) throws IOException {
    long bits = 0;
    for (int i = 0; i < 8; i++) {
      bits |= (long)data.readUnsignedByte() << (8 * i);
    }
    return Double.longBitsToDouble(bits);
  }

  private static String readString(DataInputStream data, int length) throws IOException {
    return new String(data.readNBytes(length), StandardCharsets.UTF_8);
  }
}
