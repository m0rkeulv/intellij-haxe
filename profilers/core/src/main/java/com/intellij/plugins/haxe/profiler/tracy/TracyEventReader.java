package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes tracy's DECOMPRESSED item stream (compose with
 * {@link TracyLz4Stream}) into a {@link TracySession}, scoped to what the
 * hxcpp client emits. Wire rules, verified against the client sources:
 * zone and plot times are DELTAS against a running reference that every
 * ThreadContext item RESETS to zero; memory events delta against a separate
 * serial reference; frame marks and system-load reports carry ABSOLUTE
 * times (the client's dequeue has no case for them). Every
 * ZoneBeginAllocSrcLoc is immediately preceded by its
 * SourceLocationPayload. Unknown item types are consumed by the size table
 * so future traffic degrades to being ignored, never misparsed.
 */
public final class TracyEventReader {

  private final DataInputStream data;
  private final double timerMul;

  private Hooks hooks = NO_HOOKS;
  private long refThread;
  private long refSerial;
  private int currentThread = -1;
  private @Nullable TracySourceLocation pendingSourceLocation;

  private final Set<Integer> seenThreads = new HashSet<>();
  private final Map<Integer, Deque<OpenZone>> zoneStacks = new HashMap<>();
  private final List<TracyZone> zones = new ArrayList<>();
  private final List<Long> frameMarks = new ArrayList<>();
  private final Map<Long, List<TracySession.PlotPoint>> plots = new HashMap<>();
  private final Map<Long, String> plotNames = new HashMap<>();
  private final Map<Integer, String> threadNames = new HashMap<>();
  private final List<TracySession.PlotPoint> cpuUsage = new ArrayList<>();
  private int unmatchedZoneEnds;
  private long minNs = Long.MAX_VALUE;
  private long maxNs = Long.MIN_VALUE;

  private record OpenZone(long startNs, TracySourceLocation location) {
  }

  private TracyEventReader(InputStream decompressed, TracyWelcome welcome) {
    this.data = new DataInputStream(decompressed);
    this.timerMul = welcome.timerMul();
  }

  /**
   * Live-capture callbacks: first sight of a plot or thread lets the caller
   * request its name over the query channel (the answers integrate as
   * ordinary stream items), and Terminate drives the shutdown handshake —
   * returning true stops the read. Replays pass none (a replay ends at EOF,
   * so its Terminates never stop it).
   */
  public interface Hooks {
    default void plotSeen(long namePointer) {
    }

    default void threadSeen(int threadId) {
    }

    default boolean terminateSeen() {
      return false;
    }
  }

  private static final Hooks NO_HOOKS = new Hooks() {
  };

  @NotNull
  public static TracySession read(@NotNull InputStream decompressed, @NotNull TracyWelcome welcome) throws IOException {
    return read(decompressed, welcome, NO_HOOKS);
  }

  @NotNull
  public static TracySession read(@NotNull InputStream decompressed, @NotNull TracyWelcome welcome,
                                  @NotNull Hooks hooks) throws IOException {
    TracyEventReader reader = new TracyEventReader(decompressed, welcome);
    reader.hooks = hooks;
    reader.readAll();
    return reader.freeze(welcome);
  }

  private void readAll() throws IOException {
    int typeByte;
    while ((typeByte = data.read()) >= 0) {
      TracyQueueType type = TracyQueueType.of(typeByte);
      if (type == null) throw new ProfilerFormatException("unknown tracy queue type " + typeByte);
      switch (type) {
        case ThreadContext -> {
          int thread = readIntLe();
          if (seenThreads.add(thread)) hooks.threadSeen(thread);
          currentThread = thread;
          refThread = 0;
        }
        case SourceLocationPayload -> {
          skip(8); // the client-side pointer identifying the blob - the blob itself follows
          pendingSourceLocation = TracySourceLocation.parse(readPayload(readU16Le()));
        }
        case ZoneBeginAllocSrcLoc, ZoneBeginAllocSrcLocCallstack -> beginZone(consumePendingSourceLocation());
        case ZoneBegin, ZoneBeginCallstack -> {
          // static srclocs arrive as unresolvable pointers offline; hxcpp never sends these
          long begin = advanceThreadTime();
          long pointer = readLongLe();
          openStack().push(new OpenZone(begin, staticLocation(pointer)));
          track(begin);
        }
        case ZoneEnd -> endZone();
        case ZoneValidation -> skip(4);
        case FrameMarkMsg, FrameMarkMsgStart, FrameMarkMsgEnd -> {
          long timeNs = toNs(readLongLe());
          skip(8); // name pointer; the continuous frame set sends 0
          frameMarks.add(timeNs);
          track(timeNs);
        }
        case PlotDataInt -> readPlot(PlotKind.I64);
        case PlotDataFloat -> readPlot(PlotKind.F32);
        case PlotDataDouble -> readPlot(PlotKind.F64);
        case SysTimeReport -> {
          long timeNs = toNs(readLongLe());
          cpuUsage.add(new TracySession.PlotPoint(timeNs, Float.intBitsToFloat(readIntLe())));
          track(timeNs);
        }
        case MemAlloc, MemAllocNamed, MemAllocCallstack, MemAllocCallstackNamed,
             MemFree, MemFreeNamed, MemFreeCallstack, MemFreeCallstackNamed -> {
          // not surfaced yet, but their delta keeps the serial reference honest
          refSerial += readLongLe();
          skip(type.wireSize() - 1 - 8);
        }
        // answers to the live query channel; absent in plain replays
        case PlotName -> plotNames.put(readLongLe(), new String(readPayload(readU16Le()), StandardCharsets.UTF_8));
        case ThreadName -> threadNames.put((int)readLongLe(), new String(readPayload(readU16Le()), StandardCharsets.UTF_8));
        // announces shutdown; buffered items may still follow, so only the
        // hook (owning the disconnect handshake) may declare the stream done
        case Terminate -> {
          if (hooks.terminateSeen()) return;
        }
        case KeepAlive -> {
        }
        default -> skipItem(type);
      }
    }
  }

  private enum PlotKind {
    I64,
    F32,
    F64
  }

  /** Wire order: name pointer, thread-delta time, then the kind's value. */
  private void readPlot(PlotKind kind) throws IOException {
    long name = readLongLe();
    long timeNs = advanceThreadTime();
    double value = switch (kind) {
      case I64 -> (double)readLongLe();
      case F32 -> Float.intBitsToFloat(readIntLe());
      case F64 -> Double.longBitsToDouble(readLongLe());
    };
    List<TracySession.PlotPoint> series = plots.get(name);
    if (series == null) {
      series = new ArrayList<>();
      plots.put(name, series);
      hooks.plotSeen(name);
    }
    series.add(new TracySession.PlotPoint(timeNs, value));
    track(timeNs);
  }

  private void beginZone(TracySourceLocation location) throws IOException {
    long begin = advanceThreadTime();
    openStack().push(new OpenZone(begin, location));
    track(begin);
  }

  private void endZone() throws IOException {
    long end = advanceThreadTime();
    track(end);
    Deque<OpenZone> stack = openStack();
    if (stack.isEmpty()) {
      unmatchedZoneEnds++;
      return;
    }
    OpenZone open = stack.pop();
    zones.add(new TracyZone(currentThread, open.startNs(), end, open.location()));
  }

  private TracySourceLocation consumePendingSourceLocation() throws IOException {
    TracySourceLocation location = pendingSourceLocation;
    if (location == null) {
      throw new ProfilerFormatException("zone begin without its source location payload");
    }
    pendingSourceLocation = null;
    return location;
  }

  private static TracySourceLocation staticLocation(long pointer) {
    return new TracySourceLocation("zone@" + Long.toHexString(pointer), "", 0, 0);
  }

  private Deque<OpenZone> openStack() {
    return zoneStacks.computeIfAbsent(currentThread, thread -> new ArrayDeque<>());
  }

  /** Applies one thread-stream delta and returns the new time in nanoseconds. */
  private long advanceThreadTime() throws IOException {
    refThread += readLongLe();
    return toNs(refThread);
  }

  private long toNs(long ticks) {
    return (long)(ticks * timerMul);
  }

  private void track(long ns) {
    if (ns < minNs) minNs = ns;
    if (ns > maxNs) maxNs = ns;
  }

  private TracySession freeze(TracyWelcome welcome) {
    // unclosed zones (capture cut mid-zone) end at the last seen instant
    zoneStacks.forEach((thread, stack) -> {
      for (OpenZone open : stack) {
        zones.add(new TracyZone(thread, open.startNs(), Math.max(maxNs, open.startNs()), open.location()));
      }
    });
    long base = zones.isEmpty() && minNs == Long.MAX_VALUE ? 0 : minNs;

    List<TracyZone> ordered = zones.stream()
      .map(zone -> new TracyZone(zone.threadId(), zone.startNs() - base, zone.endNs() - base, zone.location()))
      .sorted(Comparator.comparingLong(TracyZone::startNs))
      .toList();
    List<Long> frames = frameMarks.stream().map(ns -> ns - base).sorted().toList();
    Map<String, List<TracySession.PlotPoint>> rebasedPlots = new HashMap<>();
    plots.forEach((pointer, points) -> {
      String name = plotNames.getOrDefault(pointer, "plot@" + Long.toHexString(pointer));
      rebasedPlots.put(name, rebase(points, base));
    });
    long duration = maxNs == Long.MIN_VALUE ? 0 : maxNs - base;

    return new TracySession(welcome, ordered, frames, Map.copyOf(rebasedPlots), rebase(cpuUsage, base),
                            Map.copyOf(threadNames), duration, unmatchedZoneEnds);
  }

  private static List<TracySession.PlotPoint> rebase(List<TracySession.PlotPoint> points, long base) {
    return points.stream().map(point -> new TracySession.PlotPoint(point.timeNs() - base, point.value())).toList();
  }

  /** Consumes an unhandled item by the size table, payload included. */
  private void skipItem(TracyQueueType type) throws IOException {
    skip(type.wireSize() - 1);
    switch (type.payload()) {
      case NONE -> {
      }
      case U16 -> skip(readU16Le());
      case U32 -> skip(readIntLe());
    }
  }

  private void skip(int count) throws IOException {
    if (data.readNBytes(count).length < count) {
      throw new ProfilerFormatException("tracy stream ended inside an item");
    }
  }

  private byte[] readPayload(int length) throws IOException {
    byte[] payload = data.readNBytes(length);
    if (payload.length < length) throw new ProfilerFormatException("tracy stream ended inside a payload");
    return payload;
  }

  private int readU16Le() throws IOException {
    return data.readUnsignedByte() | data.readUnsignedByte() << 8;
  }

  private int readIntLe() throws IOException {
    int value = 0;
    for (int i = 0; i < 4; i++) {
      value |= data.readUnsignedByte() << (8 * i);
    }
    return value;
  }

  private long readLongLe() throws IOException {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value |= (long)data.readUnsignedByte() << (8 * i);
    }
    return value;
  }
}
