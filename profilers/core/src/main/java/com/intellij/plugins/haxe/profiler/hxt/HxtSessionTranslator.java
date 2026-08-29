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
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *              [depth, depth * nameIndex (root-first), deltaTicks],
 *          OPTIONAL trailing u32 allocatedBytes, u32 freedBytes — the
 *              window's allocation traffic from collectors that track it;
 *              records without them read as 0,
 *          OPTIONAL trailing u8 flags — bit 0 marks a COLLECTION-SEGMENT
 *              window (a transcoder's flush boundary, not a display frame:
 *              no frame event), bit 1 a record without a heap reading (no
 *              memory sample)
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

  /**
   * Reads any HXTS file: v1 headers dispatch to the sampled-capture path,
   * v5/v6 to the disk-served zone store ({@link HxtZoneStore} — zone
   * captures are too big to load whole, so they need the file, not a
   * stream; v5 differs only in carrying its small series pre-rebased).
   * v2-v4 headers are zone captures from older in-progress layouts;
   * recapture.
   */
  @NotNull
  public static HxtCapture translateCapture(@NotNull Path file) throws IOException {
    int version;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      DataInputStream data = new DataInputStream(in);
      readMagic(data);
      version = readU16(data);
    }
    return switch (version) {
      case 1 -> {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
          yield new HxtCapture.Samples(translate(in));
        }
      }
      case 5, HxtZoneWriter.VERSION -> new HxtCapture.Zones(HxtZoneStore.open(file));
      case 2, 3, 4 -> throw new ProfilerFormatException("this zone capture uses an older in-progress layout - capture it again");
      default -> throw new ProfilerFormatException("unsupported HXTS version " + version);
    };
  }

  /** The v1 (sampled) view of a stream; zone captures need {@link #translateCapture} with the file. */
  @NotNull
  public static ProfilerSnapshot translate(@NotNull InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    readMagic(data);
    int version = readU16(data);
    int tickHz = readInt(data);
    double startStamp = readDouble(data);
    String target = readString(data, readU16(data));
    if (version != 1) {
      throw new ProfilerFormatException("this HXTS file holds a zone capture, not samples");
    }
    return readSampleRecords(data, tickHz, startStamp, target, version);
  }

  /** Consumes a zone-capture header up to the records and returns its version; the store's index pass starts here. */
  static int readZoneHeader(@NotNull DataInputStream data) throws IOException {
    readMagic(data);
    int version = readU16(data);
    if (version < 5 || version > HxtZoneWriter.VERSION) {
      throw new ProfilerFormatException("unsupported HXTS zone version " + version);
    }
    readInt(data); // tick rate
    readDouble(data); // epoch - INFO carries the authoritative copy
    readString(data, readU16(data)); // target
    return version;
  }

  @NotNull
  private static ProfilerSnapshot readSampleRecords(DataInputStream data, int tickHz, double startStamp,
                                                    String target, int version) throws IOException {
    if (tickHz <= 0) throw new ProfilerFormatException("invalid tick rate: " + tickHz);

    List<String> names = new ArrayList<>();
    names.add(""); // the table is 1-indexed
    Map<Integer, StackFrame> frames = new HashMap<>();
    List<StackSample> samples = new ArrayList<>();
    List<ProfilerEvent> events = new ArrayList<>();
    List<ProfilerMemorySample> memory = new ArrayList<>();
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
          windowStart = readFrame(payload, tickHz, windowStart, names, frames, samples, events, memory);
        }
        catch (EOFException truncated) {
          break;
        }
      }
    }

    List<ProfilerThread> threads = List.of(new ProfilerThread(0, "Main"));
    return new ProfilerSnapshot(target, version, tickHz, threads, List.copyOf(samples),
                                List.copyOf(events), List.copyOf(memory));
  }

  /** Reads one frame payload; returns the next window's start (this frame's stamp). Shared with {@link HxtLiveSession}. */
  static double readFrame(byte[] payload, int tickHz, double windowStart,
                          List<String> names, Map<Integer, StackFrame> frames,
                          List<StackSample> samples, List<ProfilerEvent> events,
                          List<ProfilerMemorySample> memory) throws IOException {
    DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
    double stamp = readDouble(data);
    int gcTimeUs = readInt(data);
    readInt(data); // gcOverheadUs - not surfaced yet
    long usedBytes = Integer.toUnsignedLong(readInt(data));
    long reservedBytes = Integer.toUnsignedLong(readInt(data));

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

    // optional trailing fields - records written before them read as 0
    long allocatedBytes = data.available() >= 8 ? Integer.toUnsignedLong(readInt(data)) : 0;
    long freedBytes = data.available() >= 4 ? Integer.toUnsignedLong(readInt(data)) : 0;
    int flags = data.available() >= 1 ? data.readUnsignedByte() : 0;
    if ((flags & HxtSessionWriter.FLAG_NO_HEAP_READING) == 0) {
      memory.add(new ProfilerMemorySample(stamp, usedBytes, reservedBytes, allocatedBytes, freedBytes));
    }
    if ((flags & HxtSessionWriter.FLAG_SEGMENT_WINDOW) == 0) {
      events.add(new ProfilerEvent(stamp, 0, ProfilerEvent.FRAME_CODE, ""));
    }
    if (gcTimeUs > 0) {
      events.add(new ProfilerEvent(stamp, 0, ProfilerEvent.GC_TIME_CODE, Integer.toString(gcTimeUs)));
    }
    return stamp;
  }

  private static StackFrame frameFor(int nameIndex, List<String> names, Map<Integer, StackFrame> frames) throws IOException {
    if (nameIndex <= 0 || nameIndex >= names.size()) {
      throw new ProfilerFormatException("sample references unknown name index " + nameIndex);
    }
    return frames.computeIfAbsent(nameIndex, index -> parseFrame(names.get(index)));
  }

  /**
   * A name entry is {@code symbol} or {@code symbol(path/File.hx:123)} —
   * collectors that know source positions (flash) append them the way the
   * HL dump spells its descriptions; the hxcpp collector sends bare names.
   * A malformed suffix stays part of the symbol rather than failing.
   */
  private static StackFrame parseFrame(String name) {
    if (name.isEmpty() || name.charAt(name.length() - 1) != ')') {
      return new StackFrame(name, null, StackFrame.NO_LINE);
    }
    int open = name.lastIndexOf('(');
    int separator = name.lastIndexOf(':');
    if (open <= 0 || separator <= open) {
      return new StackFrame(name, null, StackFrame.NO_LINE);
    }
    try {
      int line = Integer.parseInt(name.substring(separator + 1, name.length() - 1).trim());
      String file = name.substring(open + 1, separator).replace('\\', '/');
      return new StackFrame(name.substring(0, open), file, line);
    }
    catch (NumberFormatException notAPosition) {
      return new StackFrame(name, null, StackFrame.NO_LINE);
    }
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
