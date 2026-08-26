package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.TimelineEvent;
import com.intellij.plugins.haxe.profiler.tracy.TracyEventReader;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * Streams an HXTS v3 zone capture to disk WHILE it is being received: zones
 * spool out in chunks as they close, so a minutes-long capture never holds
 * its zones on the heap. The small series (curves, sweeps, frames, names)
 * still accumulate in the event reader and land via {@link #finish} after
 * the stream ends, with INFO written last.
 *
 * <pre>
 * header:  'H','X','T','S', u16 version=5, u32 tickHz=0,
 *          f64 epoch (seconds), u16 targetLen + utf8 "hxcpp-tracy"
 * record:  u8 type, u32 payloadLength, payload — unknown types skippable
 * SRCLOC(3): i32 count, count * (u16+utf8 function, u16+utf8 file,
 *            i32 line, i32 color) — appended to the accumulating table,
 *            always before the first chunk that references the entries
 * ZONES (2): i32 count, i64 minStartNs, i64 maxEndNs, i64 maxDurationNs,
 *            then one raw DEFLATE stream (JDK zlib, level 6; one
 *            independent stream per chunk so random access survives) of
 *            count * (i32 threadId, u16 depth, i64 startDeltaNs,
 *            i64 durationNs, i32 srclocIndex) — CLOSE order (per thread a
 *            post-order walk of the zone tree), RAW nanoseconds (rebase
 *            base in INFO); startDeltaNs is against the PREVIOUS zone's
 *            start in the same chunk (first zone: absolute), which turns
 *            the near-monotonic starts into small values the entropy
 *            coder folds tightly
 * FRAMES(4): i32 count, count * i64 ns                  — rebased ns
 * PLOT  (5): u16+utf8 name, i32 count, count * (i64 ns, f64 value)
 * CPU   (6): i32 count, count * (i64 ns, f64 percent)
 * THREAD(8): i32 threadId, u16+utf8 name, i64 zoneCount
 * MEMORY(9): u16+utf8 poolName, i32 count, count * (i64 ns, f64 liveBytes)
 * GC   (10): i32 count, count * (i64 startNs, i64 endNs, i64 freedBytes,
 *            i32 freedObjects)
 * EVENTS(11): i32 count, count * (i32 threadId, i64 ns, i32 rgb,
 *            u16+utf8 text) — instant marks (tracy messages)
 * INFO  (7): u16+utf8 programName, u64 pid, u64 epoch, i64 durationNs,
 *            i32 unmatchedZoneEnds, i64 baseNs, u8 deflateLevel (optional
 *            trailing — provenance only, inflate never needs it) —
 *            written LAST
 * </pre>
 */
public final class HxtZoneWriter implements TracyEventReader.ZoneSink {

  static final int VERSION = 5;
  static final int INFO_RECORD = 7;
  static final int SRCLOC_RECORD = 3;
  static final int ZONES_RECORD = 2;
  static final int FRAMES_RECORD = 4;
  static final int PLOT_RECORD = 5;
  static final int CPU_RECORD = 6;
  static final int THREAD_RECORD = 8;
  static final int MEMORY_RECORD = 9;
  static final int GC_RECORD = 10;
  static final int EVENTS_RECORD = 11;

  static final int ZONE_BYTES = 4 + 2 + 8 + 8 + 4;
  private static final int CHUNK_ZONES = 65_536;
  /** The archive level a finished file settles at (zlib's knee on our data). */
  public static final int FINAL_LEVEL = 6;
  /**
   * The level a LIVE capture writes at: the profiled app runs concurrently
   * with this thread, so minimal CPU beats ratio while it is alive —
   * {@link HxtZoneRecompressor} brings the file to {@link #FINAL_LEVEL}
   * after the app has exited. Readers never care: inflate is
   * level-agnostic.
   */
  public static final int LIVE_LEVEL = 1;

  private final OutputStream out;
  private final int level;
  private final Map<TracySourceLocation, Integer> locationIndex = new LinkedHashMap<>();
  private int flushedLocations;

  private final ByteBuffer chunk = ByteBuffer.allocate(CHUNK_ZONES * ZONE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
  private int chunkZones;
  private long chunkMinStartNs;
  private long chunkMaxEndNs;
  private long chunkMaxDurationNs;
  private long previousStartNs;

  private final Map<Integer, Long> zoneCountByThread = new HashMap<>();
  private long zoneCount;
  private long baseNs;

  public HxtZoneWriter(@NotNull OutputStream out, double epochSeconds) throws IOException {
    this(out, epochSeconds, FINAL_LEVEL);
  }

  public HxtZoneWriter(@NotNull OutputStream out, double epochSeconds, int level) throws IOException {
    this.out = out;
    this.level = level;
    Payload header = new Payload();
    header.bytes.writeBytes(HxtSessionTranslator.MAGIC);
    header.u16(VERSION);
    header.i32(0); // tick rate - meaningless for exact zones
    header.f64(epochSeconds);
    header.string("hxcpp-tracy");
    out.write(header.bytes.toByteArray());
    resetChunk();
  }

  @Override
  public void zone(int threadId, int depth, long startNs, long endNs, @NotNull TracySourceLocation location) {
    Integer index = locationIndex.computeIfAbsent(location, key -> locationIndex.size());
    chunk.putInt(threadId);
    chunk.putShort((short)Math.min(depth, 0xFFFF));
    chunk.putLong(startNs - previousStartNs);
    chunk.putLong(endNs - startNs);
    chunk.putInt(index);
    previousStartNs = startNs;
    chunkZones++;
    chunkMinStartNs = Math.min(chunkMinStartNs, startNs);
    chunkMaxEndNs = Math.max(chunkMaxEndNs, endNs);
    chunkMaxDurationNs = Math.max(chunkMaxDurationNs, endNs - startNs);
    zoneCount++;
    zoneCountByThread.merge(threadId, 1L, Long::sum);
    if (chunkZones == CHUNK_ZONES) {
      try {
        flushChunk();
      }
      catch (IOException e) {
        // the sink is called from the capture's read loop, which only
        // throws IOException - surface the disk failure as one there
        throw new UncheckedIOException(e);
      }
    }
  }

  @Override
  public void finished(long base) {
    baseNs = base;
  }

  /** Zones spooled so far — the whole capture once the stream has ended. */
  public long zoneCount() {
    return zoneCount;
  }

  /**
   * Writes everything the event reader accumulated beside the zones, INFO
   * last. Call once, after the read returned the session.
   */
  public void finish(@NotNull TracySession session) throws IOException {
    flushChunk();

    Payload frames = new Payload();
    frames.i32(session.frameMarksNs().size());
    for (long ns : session.frameMarksNs()) frames.i64(ns);
    record(FRAMES_RECORD, frames);

    // deterministic order keeps the file byte-stable for identical sessions
    for (Map.Entry<String, List<TracySession.PlotPoint>> plot : new TreeMap<>(session.plots()).entrySet()) {
      Payload payload = new Payload();
      payload.string(plot.getKey());
      payload.points(plot.getValue());
      record(PLOT_RECORD, payload);
    }

    Payload cpu = new Payload();
    cpu.points(session.cpuUsage());
    record(CPU_RECORD, cpu);

    // every thread that closed zones gets a record, answered name or not -
    // the picker must list it either way
    Map<Integer, String> threadNames = new TreeMap<>(session.threadNames());
    for (Integer threadId : zoneCountByThread.keySet()) {
      threadNames.putIfAbsent(threadId, "Thread " + Integer.toUnsignedString(threadId));
    }
    for (Map.Entry<Integer, String> thread : threadNames.entrySet()) {
      Payload payload = new Payload();
      payload.i32(thread.getKey());
      payload.string(thread.getValue());
      payload.i64(zoneCountByThread.getOrDefault(thread.getKey(), 0L));
      record(THREAD_RECORD, payload);
    }

    for (Map.Entry<String, List<TracySession.PlotPoint>> curve : new TreeMap<>(session.memoryCurves()).entrySet()) {
      Payload payload = new Payload();
      payload.string(curve.getKey());
      payload.points(curve.getValue());
      record(MEMORY_RECORD, payload);
    }

    Payload sweeps = new Payload();
    sweeps.i32(session.gcSweeps().size());
    for (TracySession.GcSweep sweep : session.gcSweeps()) {
      sweeps.i64(sweep.startNs());
      sweeps.i64(sweep.endNs());
      sweeps.i64(sweep.freedBytes());
      sweeps.i32(sweep.freedObjects());
    }
    record(GC_RECORD, sweeps);

    Payload events = new Payload();
    events.i32(session.events().size());
    for (TimelineEvent event : session.events()) {
      events.i32(event.threadId());
      events.i64(event.timeNs());
      events.i32(event.color());
      events.string(event.text());
    }
    record(EVENTS_RECORD, events);

    Payload info = new Payload();
    info.string(session.welcome().programName());
    info.i64(session.welcome().pid());
    info.i64(session.welcome().epoch());
    info.i64(session.durationNs());
    info.i32(session.unmatchedZoneEnds());
    info.i64(baseNs);
    info.u8(level);
    record(INFO_RECORD, info);
  }

  /** New source locations first (chunks index into the accumulated table), then the zone chunk with its bounds. */
  private void flushChunk() throws IOException {
    if (locationIndex.size() > flushedLocations) {
      Payload locations = new Payload();
      List<TracySourceLocation> all = List.copyOf(locationIndex.keySet());
      List<TracySourceLocation> fresh = all.subList(flushedLocations, all.size());
      locations.i32(fresh.size());
      for (TracySourceLocation location : fresh) {
        locations.string(location.function());
        locations.string(location.file());
        locations.i32(location.line());
        locations.i32(location.color());
      }
      record(SRCLOC_RECORD, locations);
      flushedLocations = locationIndex.size();
    }
    if (chunkZones == 0) return;

    // the JDK's own zlib, NOT commons-compress: the latter's LZ77
    // compressor falls into a pathological slow path on real zone data
    // (~6 s per chunk, measured), and this runs on the socket thread the
    // app's exit drain waits behind; deflate also out-compresses it
    Deflater deflater = new Deflater(level);
    deflater.setInput(chunk.array(), 0, chunk.position());
    deflater.finish();
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    while (!deflater.finished()) {
      int written = deflater.deflate(buffer);
      compressed.write(buffer, 0, written);
    }
    deflater.end();

    out.write(ZONES_RECORD);
    int length = 4 + 8 + 8 + 8 + compressed.size();
    for (int i = 0; i < 4; i++) out.write(length >> (8 * i) & 0xFF);
    Payload bounds = new Payload();
    bounds.i32(chunkZones);
    bounds.i64(chunkMinStartNs);
    bounds.i64(chunkMaxEndNs);
    bounds.i64(chunkMaxDurationNs);
    out.write(bounds.bytes.toByteArray());
    compressed.writeTo(out);
    resetChunk();
  }

  private void resetChunk() {
    chunk.clear();
    chunkZones = 0;
    chunkMinStartNs = Long.MAX_VALUE;
    chunkMaxEndNs = Long.MIN_VALUE;
    chunkMaxDurationNs = 0;
    previousStartNs = 0; // each chunk's delta stream restarts, so chunks stay independent
  }

  private void record(int type, Payload payload) throws IOException {
    out.write(type);
    byte[] bytes = payload.bytes.toByteArray();
    for (int i = 0; i < 4; i++) out.write(bytes.length >> (8 * i) & 0xFF);
    out.write(bytes);
  }

  /** Little-endian payload assembly for one record. */
  private static final class Payload {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    void u8(int value) {
      bytes.write(value & 0xFF);
    }

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
