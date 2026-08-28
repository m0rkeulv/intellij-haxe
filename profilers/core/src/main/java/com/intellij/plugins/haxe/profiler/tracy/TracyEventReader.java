package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerFormatException;
import com.intellij.plugins.haxe.profiler.model.TimelineEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
 * serial reference; context switches, thread wakeups and sampled callstacks
 * against a THIRD one (never reset); frame marks and system-load reports
 * carry ABSOLUTE times (the client's dequeue has no case for them). Every
 * ZoneBeginAllocSrcLoc is immediately preceded by its
 * SourceLocationPayload. Unknown item types are consumed by the size table
 * so future traffic degrades to being ignored, never misparsed.
 */
public final class TracyEventReader {

  private final DataInputStream data;
  private final double timerMul;

  private Hooks hooks = NO_HOOKS;
  /** Null = collect zones into the session (small captures and tests). */
  private @Nullable ZoneSink zoneSink;
  /** Live captures: a torn stream ends the read and keeps what was decoded, instead of failing the capture. */
  private boolean salvageTornStream;
  private long refThread;
  private long refSerial;
  private long refCtx;
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
  /** The pool the last MemNamePayload named; consumed by the next mem event (0 = the unnamed pool). */
  private long pendingMemName;
  private final Map<Long, MemPool> memPools = new HashMap<>();
  private final Map<Long, String> strings = new HashMap<>();
  private final List<TracySession.GcSweep> gcSweeps = new ArrayList<>();
  private final List<TimelineEvent> events = new ArrayList<>();
  /** The profiled process's own pid (from the welcome) and its thread ids as TidToPid maps them. */
  private final long ownPid;
  private final Set<Long> ownTids = new HashSet<>();
  /** Per core (the wire's u8 cpu id): the thread scheduled on it and since when; -1 = not seen yet. */
  private final long[] coreTid = new long[256];
  private final long[] coreInNs = new long[256];
  private final Map<Long, Long> processBusyNsByBucket = new HashMap<>();
  /** The string a SingleStringData payload announced; the next fat item (a message) owns it. */
  private @Nullable String pendingSingleString;
  private long burstStartNs = -1;
  private long burstEndNs;
  private long burstBytes;
  private int burstObjects;
  private int unmatchedZoneEnds;
  private long minNs = Long.MAX_VALUE;
  private long maxNs = Long.MIN_VALUE;

  private record OpenZone(long startNs, TracySourceLocation location) {
  }

  /** One allocation pool's running state: live pointers with sizes, and the sampled live-bytes curve. */
  private static final class MemPool {
    final Map<Long, Long> liveSizes = new HashMap<>();
    final List<TracySession.PlotPoint> points = new ArrayList<>();
    long liveBytes;
    long lastBucket = Long.MIN_VALUE;
  }

  private TracyEventReader(InputStream decompressed, TracyWelcome welcome) {
    this.data = new DataInputStream(decompressed);
    this.timerMul = welcome.timerMul();
    this.ownPid = welcome.pid();
    Arrays.fill(coreTid, -1);
    Arrays.fill(coreInNs, -1);
  }

  /**
   * Live-capture callbacks: first sight of a plot, thread or memory pool
   * lets the caller request its name over the query channel (the answers
   * integrate as ordinary stream items), and Terminate drives the shutdown
   * handshake — returning true stops the read. Replays pass none (a replay
   * ends at EOF, so its Terminates never stop it).
   */
  public interface Hooks {
    default void plotSeen(long namePointer) {
    }

    default void threadSeen(int threadId) {
    }

    default void memPoolSeen(long namePointer) {
    }

    default boolean terminateSeen() {
      return false;
    }
  }

  /**
   * Receives every zone as it CLOSES (per thread that is a post-order walk
   * of the zone tree; depth is the zone's nesting level), letting a capture
   * spool zones to disk instead of accumulating minutes of them on the
   * heap. Times are RAW nanoseconds — the session's zero is only known at
   * the end and arrives via {@link #finished}. Without an external sink the
   * reader collects, sorts and rebases the zones into the session itself.
   */
  public interface ZoneSink {
    void zone(int threadId, int depth, long startNs, long endNs, @NotNull TracySourceLocation location);

    default void finished(long baseNs) {
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

  /**
   * The streaming form: zones go to {@code zoneSink} (raw times, close
   * order) and the returned session's zone list stays empty; everything
   * else (curves, sweeps, frames, names) is in the session as usual.
   */
  @NotNull
  public static TracySession read(@NotNull InputStream decompressed, @NotNull TracyWelcome welcome,
                                  @NotNull Hooks hooks, @NotNull ZoneSink zoneSink) throws IOException {
    TracyEventReader reader = new TracyEventReader(decompressed, welcome);
    reader.hooks = hooks;
    reader.zoneSink = zoneSink;
    reader.readAll();
    return reader.freeze(welcome);
  }

  /**
   * The LIVE-capture form: a torn stream — the profiled process killed
   * mid-run, or dead without the shutdown handshake (tracy's client exit
   * wedges when system tracing is active) — ends the read and keeps
   * everything decoded before the tear, instead of failing the whole
   * capture. Replays use the strict {@code read} forms so corrupt files
   * still fail loud.
   */
  @NotNull
  public static TracySession readSalvaging(@NotNull InputStream decompressed, @NotNull TracyWelcome welcome,
                                           @NotNull Hooks hooks,
                                           TracyEventReader.@Nullable ZoneSink zoneSink) throws IOException {
    TracyEventReader reader = new TracyEventReader(decompressed, welcome);
    reader.hooks = hooks;
    reader.zoneSink = zoneSink;
    reader.salvageTornStream = true;
    reader.readAll();
    return reader.freeze(welcome);
  }

  private void readAll() throws IOException {
    try {
      readItems();
    }
    catch (IOException torn) {
      // covers connection resets, streams cut mid-item and desyncs alike:
      // everything decoded before the fault is intact and worth keeping
      if (!salvageTornStream) throw torn;
    }
  }

  private void readItems() throws IOException {
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
        // system-tracing traffic, present only when the process ran with
        // the privileges the OS backend needs (Windows ETW: elevated).
        // Switches, wakeups and sampled callstacks share a THIRD delta
        // reference - each must advance it even where its payload is
        // discarded, or every later ctx time is wrong.
        case ContextSwitch -> readContextSwitch();
        case ThreadWakeup -> {
          advanceCtxTime();
          skip(7); // thread, cpu, adjust reason + increment
        }
        case CallstackSample, CallstackSampleContextSwitch -> {
          advanceCtxTime();
          skip(4); // thread - the sampled native stacks are not charted
        }
        case TidToPid -> {
          long tid = readLongLe();
          if (readLongLe() == ownPid) ownTids.add(tid);
        }
        // precedes the fat item owning it (a message's text)
        case SingleStringData -> pendingSingleString = new String(readPayload(readU16Le()), StandardCharsets.UTF_8);
        // message times are ABSOLUTE - the client's dequeue has no delta case for them
        case Message, MessageCallstack -> readMessage(false);
        case MessageColor, MessageColorCallstack -> readMessage(true);
        // sent under the serial lock directly before the mem event it names
        case MemNamePayload -> pendingMemName = readLongLe();
        case MemAlloc, MemAllocNamed, MemAllocCallstack, MemAllocCallstackNamed -> readMemAlloc();
        case MemFree, MemFreeNamed, MemFreeCallstack, MemFreeCallstackNamed -> readMemFree();
        case MemDiscard, MemDiscardCallstack -> {
          // hxcpp never discards a pool, but the delta keeps the serial reference honest
          advanceSerialTime();
          skip(12);
        }
        // answers to the live query channel; absent in plain replays
        case PlotName -> plotNames.put(readLongLe(), new String(readPayload(readU16Le()), StandardCharsets.UTF_8));
        case ThreadName -> threadNames.put((int)readLongLe(), new String(readPayload(readU16Le()), StandardCharsets.UTF_8));
        case StringData -> strings.put(readLongLe(), new String(readPayload(readU16Le()), StandardCharsets.UTF_8));
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

  /**
   * The smallest free run that counts as a collection: a stray large-object
   * free should not paint a GC marker.
   */
  private static final int MIN_SWEEP_FREES = 4;

  /** Wire order: serial-delta time, owning thread u32, pointer u64, 48-bit size. */
  private void readMemAlloc() throws IOException {
    long timeNs = advanceSerialTime();
    skip(4); // owning thread - the heap curves are process-wide
    long pointer = readLongLe();
    long size = readU48Le();
    closeSweep(); // an alloc means the mutator runs again - any free burst ended
    MemPool pool = memPool();
    pool.liveBytes += size;
    pool.liveSizes.put(pointer, size);
    samplePool(pool, timeNs);
    track(timeNs);
  }

  /** Wire order: serial-delta time, owning thread u32, pointer u64. */
  private void readMemFree() throws IOException {
    long timeNs = advanceSerialTime();
    skip(4);
    long pointer = readLongLe();
    MemPool pool = memPool();
    Long size = pool.liveSizes.remove(pointer);
    // an unknown pointer was allocated before the capture attached
    if (size != null) {
      pool.liveBytes -= size;
      samplePool(pool, timeNs);
    }
    extendSweep(timeNs, size);
    track(timeNs);
  }

  /** Wire order: absolute time, then b/g/r for the colored kinds; the text arrived as the preceding SingleStringData. */
  private void readMessage(boolean colored) throws IOException {
    long timeNs = toNs(readLongLe());
    int color = 0;
    if (colored) {
      int b = data.readUnsignedByte();
      int g = data.readUnsignedByte();
      int r = data.readUnsignedByte();
      color = r << 16 | g << 8 | b;
    }
    String text = pendingSingleString == null ? "" : pendingSingleString;
    pendingSingleString = null;
    events.add(new TimelineEvent(currentThread, timeNs, text, color));
    track(timeNs);
  }

  /** One process-CPU point per 100 ms of scheduler time: coarse enough to stay tiny, fine enough to zoom into. */
  private static final long PROCESS_CPU_BUCKET_NS = 100_000_000;

  /**
   * Wire order: ctx-delta time, old thread u32, new thread u32, cpu u8,
   * then wait reason/state/c-state/priorities (5 bytes). The per-core
   * tracked state closes the outgoing thread's on-core interval; only OWN
   * threads accumulate, so nothing about other processes is ever kept.
   */
  private void readContextSwitch() throws IOException {
    long timeNs = advanceCtxTime();
    skip(4); // old thread - the tracked core state already knows it
    long newThread = Integer.toUnsignedLong(readIntLe());
    int cpu = data.readUnsignedByte();
    skip(5);
    if (coreInNs[cpu] >= 0 && ownTids.contains(coreTid[cpu])) {
      accumulateProcessBusy(coreInNs[cpu], timeNs);
    }
    coreTid[cpu] = newThread;
    coreInNs[cpu] = timeNs;
  }

  /** Splits one own-thread on-core interval over the fixed buckets; the edges count as session activity. */
  private void accumulateProcessBusy(long fromNs, long toNs) {
    if (toNs <= fromNs) return;
    track(fromNs);
    track(toNs);
    long bucket = fromNs / PROCESS_CPU_BUCKET_NS;
    while (fromNs < toNs) {
      long bucketEndNs = (bucket + 1) * PROCESS_CPU_BUCKET_NS;
      processBusyNsByBucket.merge(bucket, Math.min(toNs, bucketEndNs) - fromNs, Long::sum);
      fromNs = bucketEndNs;
      bucket++;
    }
  }

  /** Frees only come from the collector (see {@link TracySession.GcSweep}), so each one extends the current burst. */
  private void extendSweep(long timeNs, @Nullable Long freedSize) {
    if (burstStartNs < 0) burstStartNs = timeNs;
    burstEndNs = timeNs;
    burstObjects++;
    if (freedSize != null) burstBytes += freedSize;
  }

  private void closeSweep() {
    if (burstStartNs >= 0 && burstObjects >= MIN_SWEEP_FREES) {
      gcSweeps.add(new TracySession.GcSweep(burstStartNs, burstEndNs, burstBytes, burstObjects));
    }
    burstStartNs = -1;
    burstBytes = 0;
    burstObjects = 0;
  }

  /** The pool the preceding MemNamePayload named, or the unnamed default pool. */
  private MemPool memPool() {
    long name = pendingMemName;
    pendingMemName = 0;
    MemPool pool = memPools.get(name);
    if (pool == null) {
      pool = new MemPool();
      memPools.put(name, pool);
      if (name != 0) hooks.memPoolSeen(name);
    }
    return pool;
  }

  /**
   * One curve point per 65 µs bucket keeps a busy allocator's curve
   * compact; within a bucket the LAST value stands, so a GC's free burst
   * still lands as a visible drop.
   */
  private static void samplePool(MemPool pool, long timeNs) {
    long bucket = timeNs >> 16;
    if (bucket == pool.lastBucket && !pool.points.isEmpty()) {
      pool.points.set(pool.points.size() - 1, new TracySession.PlotPoint(timeNs, pool.liveBytes));
    }
    else {
      pool.points.add(new TracySession.PlotPoint(timeNs, pool.liveBytes));
      pool.lastBucket = bucket;
    }
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
    emitZone(currentThread, stack.size(), open.startNs(), end, open.location());
  }

  private void emitZone(int threadId, int depth, long startNs, long endNs, TracySourceLocation location) {
    if (zoneSink != null) {
      zoneSink.zone(threadId, depth, startNs, endNs, location);
    }
    else {
      zones.add(new TracyZone(threadId, startNs, endNs, location));
    }
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

  /** Applies one serial-stream delta (memory events) and returns the new time in nanoseconds. */
  private long advanceSerialTime() throws IOException {
    refSerial += readLongLe();
    return toNs(refSerial);
  }

  /** Applies one ctx-stream delta (switches, wakeups, sampled callstacks) and returns the new time in nanoseconds. */
  private long advanceCtxTime() throws IOException {
    refCtx += readLongLe();
    return toNs(refCtx);
  }

  private long toNs(long ticks) {
    return (long)(ticks * timerMul);
  }

  private void track(long ns) {
    if (ns < minNs) minNs = ns;
    if (ns > maxNs) maxNs = ns;
  }

  private TracySession freeze(TracyWelcome welcome) {
    // unclosed zones (capture cut mid-zone) end at the last seen instant;
    // popping keeps the sink's post-order (deepest first)
    zoneStacks.forEach((thread, stack) -> {
      while (!stack.isEmpty()) {
        OpenZone open = stack.pop();
        emitZone(thread, stack.size(), open.startNs(), Math.max(maxNs, open.startNs()), open.location());
      }
    });
    long base = minNs == Long.MAX_VALUE ? 0 : minNs;
    if (zoneSink != null) {
      zoneSink.finished(base);
    }

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
    Map<String, List<TracySession.PlotPoint>> memoryCurves = new HashMap<>();
    memPools.forEach((pointer, pool) -> {
      String name = pointer == 0 ? "Memory" : strings.getOrDefault(pointer, "pool@" + Long.toHexString(pointer));
      memoryCurves.put(name, rebase(pool.points, base));
    });
    closeSweep(); // a capture cut mid-sweep still keeps the burst
    List<TracySession.GcSweep> sweeps = gcSweeps.stream()
      .map(sweep -> new TracySession.GcSweep(sweep.startNs() - base, sweep.endNs() - base,
                                             sweep.freedBytes(), sweep.freedObjects()))
      .toList();
    List<TimelineEvent> orderedEvents = events.stream()
      .map(event -> new TimelineEvent(event.threadId(), event.timeNs() - base, event.text(), event.color()))
      .sorted(Comparator.comparingLong(TimelineEvent::timeNs))
      .toList();
    long duration = maxNs == Long.MIN_VALUE ? 0 : maxNs - base;

    return new TracySession(welcome, ordered, frames, Map.copyOf(rebasedPlots), Map.copyOf(memoryCurves),
                            sweeps, orderedEvents, rebase(cpuUsage, base), processCpuPoints(base),
                            Map.copyOf(threadNames), duration, unmatchedZoneEnds);
  }

  /** The folded process-CPU curve: one point per bucket, percent of ONE core (several busy threads exceed 100). */
  private List<TracySession.PlotPoint> processCpuPoints(long base) {
    return processBusyNsByBucket.entrySet().stream()
      .sorted(Map.Entry.comparingByKey())
      .map(entry -> new TracySession.PlotPoint(Math.max(entry.getKey() * PROCESS_CPU_BUCKET_NS - base, 0),
                                               entry.getValue() * 100.0 / PROCESS_CPU_BUCKET_NS))
      .toList();
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

  private long readU48Le() throws IOException {
    long value = 0;
    for (int i = 0; i < 6; i++) {
      value |= (long)data.readUnsignedByte() << (8 * i);
    }
    return value;
  }
}
