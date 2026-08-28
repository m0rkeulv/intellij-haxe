package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.model.TimelineEvent;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import com.intellij.plugins.haxe.profiler.tracy.TracyWelcome;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A v3 zone capture served FROM ITS FILE: opening indexes the zone chunks
 * and reads the small records, but the zones themselves stay on disk —
 * {@link #scanZones} decodes only chunks whose time/duration bounds pass
 * the query, so a minutes-long capture never loads whole. Zones stream in
 * close order (per thread a post-order walk of the zone tree, depth
 * included) with times rebased to the session's zero; the small series in
 * {@link #session()} are already rebased on disk.
 */
public final class HxtZoneStore {

  private final Path file;
  private final TracySession session;
  private final List<TracySourceLocation> locations;
  private final List<ChunkRef> chunks;
  private final List<ThreadEntry> threads;
  private final long baseNs;
  private final long zoneCount;
  private final int compressionLevel;

  private record ChunkRef(long payloadOffset, int compressedLength, int zoneCount,
                          long minStartNs, long maxEndNs, long maxDurationNs) {
  }

  /** One captured thread, its resolved name and how many zones it closed. */
  public record ThreadEntry(int id, @NotNull String name, long zoneCount) {
  }

  /** Receives zones from a scan; times are session-relative nanoseconds. */
  public interface ZoneConsumer {
    void zone(int threadId, int depth, long startNs, long endNs, @NotNull TracySourceLocation location);
  }

  private HxtZoneStore(Path file, TracySession session, List<TracySourceLocation> locations,
                       List<ChunkRef> chunks, List<ThreadEntry> threads, long baseNs, long zoneCount,
                       int compressionLevel) {
    this.file = file;
    this.session = session;
    this.locations = locations;
    this.chunks = chunks;
    this.threads = threads;
    this.baseNs = baseNs;
    this.zoneCount = zoneCount;
    this.compressionLevel = compressionLevel;
  }

  /** Indexes the file: chunk offsets and bounds, the source-location table and every small record. */
  @NotNull
  public static HxtZoneStore open(@NotNull Path file) throws IOException {
    String programName = "";
    long pid = 0;
    long epoch = 0;
    long durationNs = 0;
    int unmatched = 0;
    long baseNs = 0;
    int compressionLevel = -1;
    long zoneCount = 0;
    List<TracySourceLocation> locations = new ArrayList<>();
    List<ChunkRef> chunks = new ArrayList<>();
    List<Long> frames = new ArrayList<>();
    Map<String, List<TracySession.PlotPoint>> plots = new HashMap<>();
    Map<String, List<TracySession.PlotPoint>> memoryCurves = new HashMap<>();
    List<TracySession.GcSweep> gcSweeps = new ArrayList<>();
    List<TimelineEvent> events = new ArrayList<>();
    List<TracySession.PlotPoint> cpu = new ArrayList<>();
    List<TracySession.PlotPoint> processCpu = new ArrayList<>();
    Map<Integer, String> threadNames = new HashMap<>();
    Map<Integer, Long> threadZones = new HashMap<>();

    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
      CountingStream in = new CountingStream(raw);
      DataInputStream data = new DataInputStream(in);
      HxtSessionTranslator.readZoneHeader(data);
      while (true) {
        int type;
        try {
          type = data.readUnsignedByte();
        }
        catch (EOFException end) {
          break;
        }
        int length = readI32(data);
        if (type == HxtZoneWriter.ZONES_RECORD) {
          int count = readI32(data);
          long minStart = readI64(data);
          long maxEnd = readI64(data);
          long maxDuration = readI64(data);
          int compressedLength = length - (4 + 8 + 8 + 8);
          ChunkRef chunk = new ChunkRef(in.position, compressedLength, count, minStart, maxEnd, maxDuration);
          try {
            data.skipNBytes(compressedLength);
          }
          catch (EOFException truncated) {
            break; // the capture died mid-chunk - keep the complete chunks
          }
          chunks.add(chunk);
          zoneCount += count;
          continue;
        }
        byte[] payloadBytes = data.readNBytes(length);
        if (payloadBytes.length < length) break; // truncated tail - keep what is complete
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(payloadBytes));
        switch (type) {
          case HxtZoneWriter.SRCLOC_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) {
              locations.add(new TracySourceLocation(readString(payload), readString(payload),
                                                    readI32(payload), readI32(payload)));
            }
          }
          case HxtZoneWriter.FRAMES_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) frames.add(readI64(payload));
          }
          case HxtZoneWriter.PLOT_RECORD -> plots.put(readString(payload), readPoints(payload));
          case HxtZoneWriter.CPU_RECORD -> cpu.addAll(readPoints(payload));
          case HxtZoneWriter.PROCESS_CPU_RECORD -> processCpu.addAll(readPoints(payload));
          case HxtZoneWriter.THREAD_RECORD -> {
            int id = readI32(payload);
            threadNames.put(id, readString(payload));
            threadZones.put(id, readI64(payload));
          }
          case HxtZoneWriter.MEMORY_RECORD -> memoryCurves.put(readString(payload), readPoints(payload));
          case HxtZoneWriter.GC_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) {
              gcSweeps.add(new TracySession.GcSweep(readI64(payload), readI64(payload),
                                                    readI64(payload), readI32(payload)));
            }
          }
          case HxtZoneWriter.EVENTS_RECORD -> {
            int count = readI32(payload);
            for (int i = 0; i < count; i++) {
              int eventThread = readI32(payload);
              long timeNs = readI64(payload);
              int color = readI32(payload);
              events.add(new TimelineEvent(eventThread, timeNs, readString(payload), color));
            }
          }
          case HxtZoneWriter.INFO_RECORD -> {
            programName = readString(payload);
            pid = readI64(payload);
            epoch = readI64(payload);
            durationNs = readI64(payload);
            unmatched = readI32(payload);
            baseNs = readI64(payload);
            // optional trailing field - files written before it stay readable
            if (payload.available() > 0) compressionLevel = payload.readUnsignedByte();
          }
          default -> {
            // future record types skip by their length
          }
        }
      }
    }

    TracyWelcome welcome = new TracyWelcome(1.0, 0, 0, 0, 0, epoch, 0, pid, 0, false, programName);
    TracySession session = new TracySession(welcome, List.of(), List.copyOf(frames), Map.copyOf(plots),
                                            Map.copyOf(memoryCurves), List.copyOf(gcSweeps), List.copyOf(events),
                                            List.copyOf(cpu), List.copyOf(processCpu), Map.copyOf(threadNames),
                                            durationNs, unmatched);
    List<ThreadEntry> threads = threadZones.entrySet().stream()
      .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder()))
      .map(entry -> new ThreadEntry(entry.getKey(), threadName(threadNames, entry.getKey()), entry.getValue()))
      .toList();
    return new HxtZoneStore(file, session, List.copyOf(locations), List.copyOf(chunks),
                            threads, baseNs, zoneCount, compressionLevel);
  }

  /** The capture minus its zones (the zone list is always empty — zones come from {@link #scanZones}). */
  @NotNull
  public TracySession session() {
    return session;
  }

  public long zoneCount() {
    return zoneCount;
  }

  /** The deflate level the zone chunks were written at, or -1 for a file that predates the field. Provenance only — reading never needs it. */
  public int compressionLevel() {
    return compressionLevel;
  }

  /** The captured threads, busiest first. */
  @NotNull
  public List<ThreadEntry> threads() {
    return threads;
  }

  /**
   * Streams the zones matching the query in close order. {@code fromNs} /
   * {@code toNs} bound the SESSION-RELATIVE window (pass 0 and
   * {@link Long#MAX_VALUE} for all); zones shorter than
   * {@code minDurationNs} are skipped, and chunks whose bounds cannot
   * match are never read. {@code threadId} of -1 accepts every thread.
   */
  public void scanZones(int threadId, long fromNs, long toNs, long minDurationNs,
                        @NotNull ZoneConsumer consumer) throws IOException {
    long rawFrom = fromNs == 0 ? Long.MIN_VALUE : baseNs + fromNs;
    long rawTo = toNs == Long.MAX_VALUE ? Long.MAX_VALUE : baseNs + toNs;
    try (RandomAccessFile access = new RandomAccessFile(file.toFile(), "r")) {
      byte[] compressed = new byte[0];
      byte[] raw = new byte[0];
      for (ChunkRef chunk : chunks) {
        boolean overlaps = chunk.minStartNs() <= rawTo && chunk.maxEndNs() >= rawFrom;
        if (!overlaps || chunk.maxDurationNs() < minDurationNs) continue;
        if (compressed.length < chunk.compressedLength()) compressed = new byte[chunk.compressedLength()];
        access.seek(chunk.payloadOffset());
        access.readFully(compressed, 0, chunk.compressedLength());
        int byteLength = chunk.zoneCount() * HxtZoneWriter.ZONE_BYTES;
        if (raw.length < byteLength) raw = new byte[byteLength];
        decompressChunk(compressed, chunk.compressedLength(), raw, byteLength);
        ByteBuffer zones = ByteBuffer.wrap(raw, 0, byteLength).order(ByteOrder.LITTLE_ENDIAN);
        long previousStartNs = 0;
        for (int i = 0; i < chunk.zoneCount(); i++) {
          int thread = zones.getInt();
          int depth = zones.getShort() & 0xFFFF;
          long startNs = previousStartNs + zones.getLong();
          long endNs = startNs + zones.getLong();
          int location = zones.getInt();
          previousStartNs = startNs;
          if (threadId >= 0 && thread != threadId) continue;
          if (endNs - startNs < minDurationNs) continue;
          if (startNs > rawTo || endNs < rawFrom) continue;
          if (location < 0 || location >= locations.size()) {
            throw new ProfilerFormatException("zone references unknown source location " + location);
          }
          consumer.zone(thread, depth, startNs - baseNs, endNs - baseNs, locations.get(location));
        }
      }
    }
  }

  private static void decompressChunk(byte[] compressed, int compressedLength,
                                      byte[] raw, int expectedLength) throws IOException {
    Inflater inflater = new Inflater();
    inflater.setInput(compressed, 0, compressedLength);
    try {
      int total = 0;
      while (total < expectedLength) {
        int read = inflater.inflate(raw, total, expectedLength - total);
        if (read == 0) {
          throw new ProfilerFormatException("zone chunk decompressed short: " + total + " of " + expectedLength);
        }
        total += read;
      }
    }
    catch (DataFormatException corrupted) {
      throw new ProfilerFormatException("zone chunk does not decompress: " + corrupted.getMessage());
    }
    finally {
      inflater.end();
    }
  }

  private static String threadName(Map<Integer, String> names, int threadId) {
    String name = names.get(threadId);
    return name != null ? name : "Thread " + Integer.toUnsignedString(threadId);
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

  /** Tracks the absolute file position so chunk payload offsets can be recorded. */
  private static final class CountingStream extends InputStream {
    private final InputStream in;
    long position;

    CountingStream(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0) position++;
      return value;
    }

    @Override
    public int read(byte @NotNull [] buffer, int offset, int length) throws IOException {
      int read = in.read(buffer, offset, length);
      if (read > 0) position += read;
      return read;
    }
  }
}
