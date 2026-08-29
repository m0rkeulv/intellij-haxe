package com.intellij.plugins.haxe.profiler.tracy;

import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.FlameNode;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.UsSpan;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chart projections of a zone capture. Zones are EXACT intervals, so the
 * flame tree needs no sampling reconstruction: nesting is containment, and
 * every box width is a measured duration. Times convert from the session's
 * nanoseconds to the charts' microsecond axis.
 */
public final class TracyZoneTrees {

  private TracyZoneTrees() {
  }

  /**
   * The thread's zones as a time-ordered flame tree — the call chart's
   * input. Idle fillers keep children time-true, like the sampled builder;
   * {@code samples} is 1 per node: a zone is one measured run.
   */
  @NotNull
  public static FlameNode threadTree(@NotNull TracySession session, int threadId) {
    List<TracyZone> ordered = session.zones().stream()
      .filter(zone -> zone.threadId() == threadId)
      .toList();
    return treeFromOrdered(ordered, session.durationNs());
  }

  /** Builds the flame tree from one thread's START-ORDERED zones (windowed loads sort before calling). */
  @NotNull
  public static FlameNode treeFromOrdered(@NotNull List<TracyZone> ordered, long durationNs) {
    Builder root = new Builder(null, 0, Math.max(durationNs / 1000, 1));
    Deque<Builder> open = new ArrayDeque<>();
    for (TracyZone zone : ordered) {
      long startUs = zone.startNs() / 1000;
      long endUs = Math.max(zone.endNs() / 1000, startUs + 1);
      while (!open.isEmpty() && startUs >= open.peek().endUs) {
        open.pop();
      }
      Builder parent = open.isEmpty() ? root : open.peek();
      // exact data can still collide on the µs grid; clamp inside the parent
      Builder child = new Builder(frameOf(zone.location()),
                                  Math.max(startUs, parent.startUs),
                                  Math.min(Math.max(endUs, startUs + 1), parent.endUs));
      parent.children.add(child);
      open.push(child);
    }
    return root.freeze();
  }

  /**
   * Consecutive frame marks as frame spans for the Frames lane. Marks come
   * from hxcpp's telemetry frame hook, which frameworks drive once per
   * frame (lime/openfl do); a plain hxcpp run has no caller and its lane
   * stays empty.
   */
  @NotNull
  public static List<UsSpan> frameSpans(@NotNull TracySession session) {
    List<UsSpan> spans = new ArrayList<>();
    Long previous = null;
    for (long markNs : session.frameMarksNs()) {
      if (previous != null && markNs > previous) {
        spans.add(new UsSpan(previous / 1000, markNs / 1000));
      }
      previous = markNs;
    }
    return spans;
  }

  /** The captured threads, busiest first, named when a ThreadName answer arrived. */
  @NotNull
  public static List<ProfilerThread> threads(@NotNull TracySession session) {
    Map<Integer, Long> zoneCounts = new HashMap<>();
    for (TracyZone zone : session.zones()) {
      zoneCounts.merge(zone.threadId(), 1L, Long::sum);
    }
    return zoneCounts.entrySet().stream()
      .sorted(Comparator.comparingLong(Map.Entry<Integer, Long>::getValue).reversed())
      .map(entry -> new ProfilerThread(entry.getKey(), threadName(session, entry.getKey())))
      .toList();
  }

  @NotNull
  private static String threadName(TracySession session, int threadId) {
    String name = session.threadNames().get(threadId);
    return name != null ? name : "Thread " + Integer.toUnsignedString(threadId);
  }

  @NotNull
  private static StackFrame frameOf(TracySourceLocation location) {
    String file = location.file().isEmpty() ? null : location.file();
    return new StackFrame(location.function(), file, location.line() > 0 ? location.line() : StackFrame.NO_LINE);
  }

  /** Mirrors the sampled builder's freeze: children plus idle fillers covering the parent. */
  private static final class Builder {
    final StackFrame frame;
    final long startUs;
    final long endUs;
    final List<Builder> children = new ArrayList<>();

    Builder(StackFrame frame, long startUs, long endUs) {
      this.frame = frame;
      this.startUs = startUs;
      this.endUs = endUs;
    }

    FlameNode freeze() {
      List<FlameNode> frozen = new ArrayList<>(children.size());
      long covered = startUs;
      for (Builder child : children) {
        if (child.startUs > covered) {
          frozen.add(new FlameNode(null, covered, child.startUs, 0, List.of(), true));
        }
        frozen.add(child.freeze());
        covered = child.endUs;
      }
      if (!frozen.isEmpty() && covered < endUs) {
        frozen.add(new FlameNode(null, covered, endUs, 0, List.of(), true));
      }
      return new FlameNode(frame, startUs, endUs, 1, List.copyOf(frozen), false);
    }
  }
}
