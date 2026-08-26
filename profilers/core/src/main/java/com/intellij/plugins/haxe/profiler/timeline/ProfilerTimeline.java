package com.intellij.plugins.haxe.profiler.timeline;

import com.intellij.plugins.haxe.profiler.model.ProfilerEvent;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time projections of a snapshot for timeline views: per-thread activity
 * spans, sampled-activity and GC series, and frame durations from the
 * target's end-of-frame markers. All times are MILLISECONDS relative to
 * {@link #captureStartSeconds} so viewers get zero-based axes.
 * <p>
 * The activity series is sample DENSITY, not CPU usage — a sampling dump
 * carries no scheduler data. A target that can measure real CPU load feeds
 * it as another series next to these.
 */
public final class ProfilerTimeline {

  /** A span on a thread's lane and how many samples landed inside it. */
  public record TimeSpan(long startMs, long endMs, int sampleCount) {
  }

  /** One chart point. */
  public record SeriesPoint(long timeMs, double value) {
  }

  /**
   * A node of the time-ordered flame tree: one function's contiguous run,
   * its callee runs as children in time order. Times are MICROSECONDS —
   * capture rates reach 10000/s, so millisecond rounding would collapse
   * neighbouring samples onto one timestamp and make sibling runs overlap.
   * The root has a null frame and spans the thread's whole sampled range;
   * an {@code idle} node fills a span where the PARENT ran but no callee
   * was sampled (or, on the root, where the thread was not sampled at
   * all), keeping sibling positions time-true.
   */
  public record FlameNode(@Nullable StackFrame frame, long startUs, long endUs, int samples,
                          @NotNull List<FlameNode> children, boolean idle) {
    public long durationUs() {
      return endUs - startUs;
    }
  }

  private ProfilerTimeline() {
  }

  /** The capture's epoch: the earliest sample or event timestamp, 0 for an empty capture. */
  public static double captureStartSeconds(@NotNull ProfilerSnapshot snapshot) {
    double start = Double.MAX_VALUE;
    for (StackSample sample : snapshot.samples()) {
      start = Math.min(start, sample.time());
    }
    for (ProfilerEvent event : snapshot.events()) {
      start = Math.min(start, event.time());
    }
    return start == Double.MAX_VALUE ? 0 : start;
  }

  /** The capture's total extent in milliseconds. */
  public static long durationMs(@NotNull ProfilerSnapshot snapshot) {
    double start = captureStartSeconds(snapshot);
    double end = start;
    for (StackSample sample : snapshot.samples()) {
      end = Math.max(end, sample.time());
    }
    for (ProfilerEvent event : snapshot.events()) {
      end = Math.max(end, event.time());
    }
    return Math.round((end - start) * 1000);
  }

  /**
   * The thread's sampled activity as fixed-width blocks: one span per
   * {@code blockMs}-wide bucket that contains samples, empty buckets leaving
   * lane gaps. Blocks are the clickable timeline unit — each maps back to
   * its bucket's samples for {@link #dominantStack}.
   */
  // TODO: unconsumed since the Timeline tab's removal - intended for the live-view charts.
  @NotNull
  public static List<TimeSpan> activityBlocks(@NotNull ProfilerSnapshot snapshot, int threadId, long blockMs) {
    if (blockMs <= 0) return List.of();
    double start = captureStartSeconds(snapshot);

    List<TimeSpan> blocks = new ArrayList<>();
    long currentBlock = -1;
    int count = 0;
    for (StackSample sample : snapshot.samples()) {
      if (sample.threadId() != threadId) continue;
      long block = Math.round((sample.time() - start) * 1000) / blockMs;
      if (block != currentBlock) {
        if (count > 0) {
          blocks.add(new TimeSpan(currentBlock * blockMs, (currentBlock + 1) * blockMs, count));
        }
        currentBlock = block;
        count = 0;
      }
      count++;
    }
    if (count > 0) {
      blocks.add(new TimeSpan(currentBlock * blockMs, (currentBlock + 1) * blockMs, count));
    }
    return blocks;
  }

  /**
   * The thread's samples as a time-ordered flame tree (what Chrome renders
   * for a cpuProfile): a run extends while consecutive samples keep the same
   * frame AND every ancestor above it; a sampling gap far beyond the period
   * breaks every run. Runs tile — each ends one sampling period after its
   * last sample, CLAMPED to the next sample's time so timer jitter (samples
   * arriving closer than the nominal period) can never overlap siblings.
   * Idle fillers keep children time-true within their parent. Frames below
   * {@code maxDepth} fold into their ancestor (the flamegraph tab still has
   * the full depth).
   */
  @NotNull
  public static FlameNode flameTree(@NotNull ProfilerSnapshot snapshot, int threadId, int maxDepth) {
    long periodUs = periodUs(snapshot.samplesPerSecond());
    long gapUs = Math.max(5 * periodUs, 20_000);
    double start = captureStartSeconds(snapshot);

    NodeBuilder root = new NodeBuilder(null, 0);
    List<NodeBuilder> open = new ArrayList<>(); // the currently running path, root's child first
    long previousUs = Long.MIN_VALUE;
    boolean any = false;
    for (StackSample sample : snapshot.samples()) {
      if (sample.threadId() != threadId) continue;
      long timeUs = Math.round((sample.time() - start) * 1_000_000);
      if (!any) {
        root.startUs = timeUs;
        any = true;
      }
      boolean gapBroken = previousUs != Long.MIN_VALUE && timeUs - previousUs > gapUs;
      previousUs = timeUs;

      List<StackFrame> frames = sample.frames();
      int depth = Math.min(frames.size(), maxDepth);
      int common = gapBroken ? 0 : commonPrefix(open, frames, depth);
      // deeper runs ended (already attached to their parents); a run that
      // optimistically extended one period past its last sample gives the
      // overshoot back so the sibling opening NOW cannot overlap it
      for (NodeBuilder ended : open.subList(common, open.size())) {
        ended.endUs = Math.min(ended.endUs, Math.max(timeUs, ended.startUs));
      }
      open.subList(common, open.size()).clear();
      for (int d = common; d < depth; d++) {
        NodeBuilder parent = d == 0 ? root : open.get(d - 1);
        NodeBuilder child = new NodeBuilder(frames.get(d), timeUs);
        parent.children.add(child);
        open.add(child);
      }
      long endUs = timeUs + periodUs;
      root.samples++;
      root.endUs = endUs;
      for (NodeBuilder builder : open) {
        builder.samples++;
        builder.endUs = endUs;
      }
    }
    return root.freeze();
  }

  /**
   * The run covering {@code timeUs} at the given tree level of a
   * {@link #flameTree} (level 0 = the root, level 1 = its children).
   * Returns the idle filler when that is what covers the spot, and null when
   * the level does not reach it — the call chart's hit test.
   */
  @Nullable
  public static FlameNode nodeAt(@NotNull FlameNode root, long timeUs, int level) {
    if (timeUs < root.startUs() || timeUs >= root.endUs()) return null;
    FlameNode current = root;
    for (int i = 0; i < level; i++) {
      current = childAt(current, timeUs);
      if (current == null) return null;
    }
    return current;
  }

  /** A span on the capture's microsecond time axis (shared with {@link #flameTree}). */
  public record UsSpan(long startUs, long endUs) {
    public long durationUs() {
      return endUs - startUs;
    }
  }

  /**
   * Contiguous runs of GC-flagged samples on the thread as spans, each
   * extended one sampling period like the flame runs; a clean sample or a
   * sampling gap ends the span.
   */
  @NotNull
  public static List<UsSpan> gcSpans(@NotNull ProfilerSnapshot snapshot, int threadId) {
    long periodUs = periodUs(snapshot.samplesPerSecond());
    long gapUs = Math.max(5 * periodUs, 20_000);
    double start = captureStartSeconds(snapshot);

    List<UsSpan> spans = new ArrayList<>();
    long spanStartUs = -1;
    long spanEndUs = 0;
    for (StackSample sample : snapshot.samples()) {
      if (sample.threadId() != threadId) continue;
      long timeUs = Math.round((sample.time() - start) * 1_000_000);
      boolean continues = sample.inGc() && spanStartUs >= 0 && timeUs - spanEndUs <= gapUs;
      if (continues) {
        spanEndUs = timeUs + periodUs;
        continue;
      }
      if (spanStartUs >= 0) {
        spans.add(new UsSpan(spanStartUs, spanEndUs));
        spanStartUs = -1;
      }
      if (sample.inGc()) {
        spanStartUs = timeUs;
        spanEndUs = timeUs + periodUs;
      }
    }
    if (spanStartUs >= 0) {
      spans.add(new UsSpan(spanStartUs, spanEndUs));
    }
    return spans;
  }

  /**
   * The thread's frames from its end-of-frame markers (event code 0): one
   * span per completed frame, between consecutive markers. Empty when the
   * app emits no frame events.
   */
  @NotNull
  public static List<UsSpan> frameSpans(@NotNull ProfilerSnapshot snapshot, int threadId) {
    double start = captureStartSeconds(snapshot);
    List<UsSpan> spans = new ArrayList<>();
    double previousFrameEnd = Double.NaN;
    for (ProfilerEvent event : snapshot.events()) {
      if (event.threadId() != threadId || event.code() != ProfilerEvent.FRAME_CODE) continue;
      if (!Double.isNaN(previousFrameEnd)) {
        spans.add(new UsSpan(Math.round((previousFrameEnd - start) * 1_000_000),
                             Math.round((event.time() - start) * 1_000_000)));
      }
      previousFrameEnd = event.time();
    }
    return spans;
  }

  /**
   * GC spans from per-frame GC-time events ({@link ProfilerEvent#GC_TIME_CODE},
   * data = microseconds) — the shape telemetry captures report GC in: a
   * total per frame, not flagged samples. Each span sits at the END of its
   * frame with the reported width, clamped so spans never overlap: the
   * DURATION is exact, the position only frame-accurate.
   */
  @NotNull
  public static List<UsSpan> gcSpansFromEvents(@NotNull ProfilerSnapshot snapshot) {
    double start = captureStartSeconds(snapshot);
    List<UsSpan> spans = new ArrayList<>();
    long previousEndUs = 0;
    for (ProfilerEvent event : snapshot.events()) {
      if (event.code() != ProfilerEvent.GC_TIME_CODE) continue;
      long gcUs = parsedMicros(event.data());
      if (gcUs <= 0) continue;
      long endUs = Math.round((event.time() - start) * 1_000_000);
      long startUs = Math.max(endUs - gcUs, previousEndUs);
      if (startUs >= endUs) continue;
      spans.add(new UsSpan(startUs, endUs));
      previousEndUs = endUs;
    }
    return spans;
  }

  private static long parsedMicros(String data) {
    try {
      return Long.parseLong(data);
    }
    catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * The chain of runs covering {@code timeUs}, outermost first, from level 1
   * down to at most {@code level} — the call stack at that spot. Stops above
   * an idle filler (a filler is no call); empty when the time lies outside
   * the capture.
   */
  @NotNull
  public static List<FlameNode> pathTo(@NotNull FlameNode root, long timeUs, int level) {
    List<FlameNode> path = new ArrayList<>();
    FlameNode current = root;
    for (int i = 0; i < level; i++) {
      current = childAt(current, timeUs);
      if (current == null || current.idle()) break;
      path.add(current);
    }
    return path;
  }

  /** Binary search over the time-ordered children; null in a coverage gap. */
  @Nullable
  private static FlameNode childAt(@NotNull FlameNode parent, long timeUs) {
    List<FlameNode> children = parent.children();
    int low = 0;
    int high = children.size() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      FlameNode child = children.get(middle);
      if (timeUs < child.startUs()) {
        high = middle - 1;
      }
      else if (timeUs >= child.endUs()) {
        low = middle + 1;
      }
      else {
        return child;
      }
    }
    return null;
  }

  /** How many levels of runs hang below the root — the call chart's row count. */
  public static int treeDepth(@NotNull FlameNode root) {
    int deepest = 0;
    for (FlameNode child : root.children()) {
      deepest = Math.max(deepest, treeDepth(child) + 1);
    }
    return deepest;
  }

  private static int commonPrefix(List<NodeBuilder> open, List<StackFrame> frames, int depth) {
    int common = 0;
    while (common < open.size() && common < depth && frames.get(common).equals(open.get(common).frame)) {
      common++;
    }
    return common;
  }

  private static long periodUs(int samplesPerSecond) {
    return samplesPerSecond > 0 ? Math.max(1, Math.round(1_000_000.0 / samplesPerSecond)) : 1000;
  }

  private static final class NodeBuilder {
    final @Nullable StackFrame frame;
    final List<NodeBuilder> children = new ArrayList<>();
    long startUs;
    long endUs;
    int samples;

    NodeBuilder(@Nullable StackFrame frame, long startUs) {
      this.frame = frame;
      this.startUs = startUs;
      this.endUs = startUs;
    }

    /** Freezes the subtree, inserting idle fillers wherever children leave part of this node uncovered. */
    FlameNode freeze() {
      List<FlameNode> frozen = new ArrayList<>(children.size());
      long covered = startUs;
      for (NodeBuilder child : children) {
        if (child.startUs > covered) {
          frozen.add(new FlameNode(null, covered, child.startUs, 0, List.of(), true));
        }
        frozen.add(child.freeze());
        covered = child.endUs;
      }
      if (!frozen.isEmpty() && covered < endUs) {
        frozen.add(new FlameNode(null, covered, endUs, 0, List.of(), true));
      }
      return new FlameNode(frame, startUs, endUs, samples, List.copyOf(frozen), false);
    }
  }

  /**
   * The most frequent identical stack among the thread's samples inside
   * {@code [fromMs, toMs)} — what the thread was doing in a clicked block.
   * Empty when no sample falls inside. Frames come back ROOT-FIRST like the
   * samples carry them; a tie keeps the earliest-seen stack.
   */
  // TODO: unconsumed since the Timeline tab's removal - intended for the live-view charts.
  @NotNull
  public static List<StackFrame> dominantStack(@NotNull ProfilerSnapshot snapshot, int threadId, long fromMs, long toMs) {
    double start = captureStartSeconds(snapshot);

    Map<List<StackFrame>, Integer> counts = new LinkedHashMap<>();
    for (StackSample sample : snapshot.samples()) {
      if (sample.threadId() != threadId) continue;
      long timeMs = Math.round((sample.time() - start) * 1000);
      if (timeMs < fromMs || timeMs >= toMs) continue;
      counts.merge(sample.frames(), 1, Integer::sum);
    }

    List<StackFrame> dominant = List.of();
    int best = 0;
    for (Map.Entry<List<StackFrame>, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > best) {
        best = entry.getValue();
        dominant = entry.getKey();
      }
    }
    return dominant;
  }

  /** Samples per second across ALL threads, bucketed; GC-flagged samples only when {@code gcOnly}. */
  // TODO: unconsumed since the Timeline tab's removal - intended for the live-view charts.
  @NotNull
  public static List<SeriesPoint> activitySeries(@NotNull ProfilerSnapshot snapshot, long bucketMs, boolean gcOnly) {
    if (snapshot.samples().isEmpty() || bucketMs <= 0) return List.of();
    double start = captureStartSeconds(snapshot);
    int bucketCount = (int)(durationMs(snapshot) / bucketMs) + 1;
    int[] counts = new int[bucketCount];
    for (StackSample sample : snapshot.samples()) {
      if (gcOnly && !sample.inGc()) continue;
      int bucket = (int)(Math.round((sample.time() - start) * 1000) / bucketMs);
      if (bucket >= 0 && bucket < bucketCount) counts[bucket]++;
    }

    List<SeriesPoint> series = new ArrayList<>(bucketCount);
    for (int i = 0; i < bucketCount; i++) {
      series.add(new SeriesPoint(i * bucketMs, counts[i] * 1000.0 / bucketMs));
    }
    return series;
  }

  /**
   * Frame durations from the thread's end-of-frame markers (event code 0):
   * one point per completed frame, placed at the frame's end, value =
   * milliseconds the frame took. Empty when the app emits no frame events.
   */
  // TODO: unconsumed since the Timeline tab's removal - intended for the live-view charts.
  @NotNull
  public static List<SeriesPoint> frameDurationSeries(@NotNull ProfilerSnapshot snapshot, int threadId) {
    double start = captureStartSeconds(snapshot);
    List<SeriesPoint> series = new ArrayList<>();
    double previousFrameEnd = Double.NaN;
    for (ProfilerEvent event : snapshot.events()) {
      if (event.threadId() != threadId || event.code() != ProfilerEvent.FRAME_CODE) continue;
      if (!Double.isNaN(previousFrameEnd)) {
        long timeMs = Math.round((event.time() - start) * 1000);
        series.add(new SeriesPoint(timeMs, (event.time() - previousFrameEnd) * 1000));
      }
      previousFrameEnd = event.time();
    }
    return series;
  }
}
