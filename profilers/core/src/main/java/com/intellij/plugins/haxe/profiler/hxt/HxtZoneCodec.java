package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import com.intellij.plugins.haxe.profiler.tracy.TracyWelcome;
import com.intellij.plugins.haxe.profiler.tracy.TracyZone;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The HXTS v2 record set: an exact zone capture persisted for later
 * opening. Same container rules as v1 — length-prefixed records, unknown
 * types skippable — with source locations interned into an accumulating
 * table the zone records index. Times are already session-relative
 * nanoseconds, so a reopened session needs no timer scale: its welcome
 * carries identity only (program name, pid, epoch).
 *
 * <pre>
 * INFO  (7): u16+utf8 programName, u64 pid, u64 epoch,
 *            i64 durationNs, i32 unmatchedZoneEnds
 * SRCLOC(3): i32 count, count * (u16+utf8 function, u16+utf8 file,
 *            i32 line, i32 color) — appended to the session's table
 * ZONES (2): i32 count, count * (i32 threadId, i64 startNs, i64 endNs,
 *            i32 sourceLocationIndex)
 * FRAMES(4): i32 count, count * i64 ns
 * PLOT  (5): u16+utf8 name, i32 count, count * (i64 ns, f64 value)
 * CPU   (6): i32 count, count * (i64 ns, f64 percent)
 * THREAD(8): i32 threadId, u16+utf8 name
 * </pre>
 */
public final class HxtZoneCodec {

  static final int INFO_RECORD = 7;
  static final int SRCLOC_RECORD = 3;
  static final int ZONES_RECORD = 2;
  static final int FRAMES_RECORD = 4;
  static final int PLOT_RECORD = 5;
  static final int CPU_RECORD = 6;
  static final int THREAD_RECORD = 8;

  /** Bounds one ZONES record to ~24 MB so record lengths stay tame on huge captures. */
  private static final int ZONES_PER_RECORD = 1_000_000;

  private HxtZoneCodec() {
  }

  /** Writes the complete v2 file: container header plus the session's records. */
  public static void write(@NotNull TracySession session, @NotNull OutputStream out) throws IOException {
    Payload header = new Payload();
    header.bytes.writeBytes(HxtSessionTranslator.MAGIC);
    header.u16(2);
    header.i32(0); // tick rate - meaningless for exact zones
    header.f64(session.welcome().epoch());
    header.string("hxcpp-tracy");
    out.write(header.bytes.toByteArray());

    Payload info = new Payload();
    info.string(session.welcome().programName());
    info.i64(session.welcome().pid());
    info.i64(session.welcome().epoch());
    info.i64(session.durationNs());
    info.i32(session.unmatchedZoneEnds());
    record(out, INFO_RECORD, info);

    Map<TracySourceLocation, Integer> locationIndex = new LinkedHashMap<>();
    for (TracyZone zone : session.zones()) {
      locationIndex.computeIfAbsent(zone.location(), location -> locationIndex.size());
    }
    Payload locations = new Payload();
    locations.i32(locationIndex.size());
    for (TracySourceLocation location : locationIndex.keySet()) {
      locations.string(location.function());
      locations.string(location.file());
      locations.i32(location.line());
      locations.i32(location.color());
    }
    record(out, SRCLOC_RECORD, locations);

    List<TracyZone> zones = session.zones();
    int from = 0;
    do {
      List<TracyZone> chunk = zones.subList(from, Math.min(zones.size(), from + ZONES_PER_RECORD));
      Payload payload = new Payload();
      payload.i32(chunk.size());
      for (TracyZone zone : chunk) {
        payload.i32(zone.threadId());
        payload.i64(zone.startNs());
        payload.i64(zone.endNs());
        payload.i32(locationIndex.get(zone.location()));
      }
      record(out, ZONES_RECORD, payload);
      from += ZONES_PER_RECORD;
    } while (from < zones.size());

    Payload frames = new Payload();
    frames.i32(session.frameMarksNs().size());
    for (long ns : session.frameMarksNs()) frames.i64(ns);
    record(out, FRAMES_RECORD, frames);

    // deterministic order keeps the file byte-stable for identical sessions
    for (Map.Entry<String, List<TracySession.PlotPoint>> plot : new TreeMap<>(session.plots()).entrySet()) {
      Payload payload = new Payload();
      payload.string(plot.getKey());
      payload.points(plot.getValue());
      record(out, PLOT_RECORD, payload);
    }

    Payload cpu = new Payload();
    cpu.points(session.cpuUsage());
    record(out, CPU_RECORD, cpu);

    for (Map.Entry<Integer, String> thread : new TreeMap<>(session.threadNames()).entrySet()) {
      Payload payload = new Payload();
      payload.i32(thread.getKey());
      payload.string(thread.getValue());
      record(out, THREAD_RECORD, payload);
    }
  }

  /** Reads the records of a v2 file; the container header was already consumed by the translator. */
  @NotNull
  static TracySession readRecords(@NotNull DataInputStream data, double headerEpoch) throws IOException {
    String programName = "";
    long pid = 0;
    long epoch = (long)headerEpoch;
    long durationNs = 0;
    int unmatched = 0;
    List<TracySourceLocation> locations = new ArrayList<>();
    List<TracyZone> zones = new ArrayList<>();
    List<Long> frames = new ArrayList<>();
    Map<String, List<TracySession.PlotPoint>> plots = new HashMap<>();
    List<TracySession.PlotPoint> cpu = new ArrayList<>();
    Map<Integer, String> threadNames = new HashMap<>();

    while (true) {
      int type;
      try {
        type = data.readUnsignedByte();
      }
      catch (EOFException end) {
        break;
      }
      byte[] payloadBytes;
      try {
        payloadBytes = data.readNBytes(readI32(data));
      }
      catch (EOFException truncated) {
        break; // keep what is complete, like the v1 path
      }
      DataInputStream payload = new DataInputStream(new ByteArrayInputStream(payloadBytes));
      try {
        switch (type) {
          case INFO_RECORD -> {
            programName = readString(payload);
            pid = readI64(payload);
            epoch = readI64(payload);
            durationNs = readI64(payload);
            unmatched = readI32(payload);
          }
          case SRCLOC_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) {
              locations.add(new TracySourceLocation(readString(payload), readString(payload),
                                                    readI32(payload), readI32(payload)));
            }
          }
          case ZONES_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) {
              int thread = readI32(payload);
              long start = readI64(payload);
              long end = readI64(payload);
              int index = readI32(payload);
              if (index < 0 || index >= locations.size()) {
                throw new ProfilerFormatException("zone references unknown source location " + index);
              }
              zones.add(new TracyZone(thread, start, end, locations.get(index)));
            }
          }
          case FRAMES_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) frames.add(readI64(payload));
          }
          case PLOT_RECORD -> plots.put(readString(payload), readPoints(payload));
          case CPU_RECORD -> cpu.addAll(readPoints(payload));
          case THREAD_RECORD -> threadNames.put(readI32(payload), readString(payload));
          default -> {
            // future record types skip by their length
          }
        }
      }
      catch (EOFException truncated) {
        throw new ProfilerFormatException("truncated record of type " + type);
      }
    }

    TracyWelcome welcome = new TracyWelcome(1.0, 0, 0, 0, 0, epoch, 0, pid, 0, false, programName);
    return new TracySession(welcome, List.copyOf(zones), List.copyOf(frames), Map.copyOf(plots),
                            List.copyOf(cpu), Map.copyOf(threadNames), durationNs, unmatched);
  }

  private static void record(OutputStream out, int type, Payload payload) throws IOException {
    out.write(type);
    byte[] bytes = payload.bytes.toByteArray();
    for (int i = 0; i < 4; i++) out.write(bytes.length >> (8 * i) & 0xFF);
    out.write(bytes);
  }

  private static List<TracySession.PlotPoint> readPoints(DataInputStream payload) throws IOException {
    int count = readI32(payload);
    List<TracySession.PlotPoint> points = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      points.add(new TracySession.PlotPoint(readI64(payload), Double.longBitsToDouble(readI64(payload))));
    }
    return points;
  }

  private static String readString(DataInputStream payload) throws IOException {
    int length = payload.readUnsignedByte() | payload.readUnsignedByte() << 8;
    byte[] utf8 = payload.readNBytes(length);
    if (utf8.length < length) throw new EOFException();
    return new String(utf8, StandardCharsets.UTF_8);
  }

  private static int readI32(DataInputStream data) throws IOException {
    int value = 0;
    for (int i = 0; i < 4; i++) value |= data.readUnsignedByte() << (8 * i);
    return value;
  }

  private static long readI64(DataInputStream data) throws IOException {
    long value = 0;
    for (int i = 0; i < 8; i++) value |= (long)data.readUnsignedByte() << (8 * i);
    return value;
  }

  /** Little-endian payload assembly for one record. */
  private static final class Payload {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    void u16(int value) {
      bytes.write(value & 0xFF);
      bytes.write(value >> 8 & 0xFF);
    }

    void i32(int value) {
      for (int i = 0; i < 4; i++) bytes.write(value >> (8 * i) & 0xFF);
    }

    void i64(long value) {
      for (int i = 0; i < 8; i++) bytes.write((int)(value >> (8 * i) & 0xFF));
    }

    void f64(double value) {
      i64(Double.doubleToLongBits(value));
    }

    void string(String value) {
      byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
      u16(utf8.length);
      bytes.writeBytes(utf8);
    }

    void points(List<TracySession.PlotPoint> points) {
      i32(points.size());
      for (TracySession.PlotPoint point : points) {
        i64(point.timeNs());
        i64(Double.doubleToLongBits(point.value()));
      }
    }
  }
}
