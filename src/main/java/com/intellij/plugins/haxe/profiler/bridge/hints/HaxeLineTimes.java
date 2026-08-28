package com.intellij.plugins.haxe.profiler.bridge.hints;

import com.intellij.plugins.haxe.profiler.bridge.data.HaxeSamplingProfilerData;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneStore;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A capture's time aggregated per source line, for the editor gutter
 * hints. Sampled captures (HL) attribute like the Java hints: every frame's
 * line is the CALL SITE inside its own method, so a line knows the method
 * enclosing it and that method's total. Tracy zones only carry the callee
 * function's declaration line (the caller's current line never reaches the
 * wire), so tracy hints are per function with no enclosing share. Lines
 * whose total rounds below a microsecond are dropped rather than shown as
 * zero. Files are keyed by their forward-slashed path as the capture
 * spelled it; editors match by path suffix.
 */
final class HaxeLineTimes {

  /** {@code enclosing} is the method's displayable name ("get", "setup.2"); null for per-function (tracy) attribution. */
  record LineTime(long totalUs, long selfUs, @Nullable String enclosing, long enclosingTotalUs) {
  }

  private final Map<String, Map<Integer, LineTime>> byFile;
  private final long sessionUs;
  /** Editor paths resolve to the same file table repeatedly; remember the verdict. */
  private final Map<String, Map<Integer, LineTime>> byEditorPath = new HashMap<>();

  private HaxeLineTimes(Map<String, Map<Integer, LineTime>> byFile, long sessionUs) {
    this.byFile = byFile;
    this.sessionUs = Math.max(sessionUs, 1);
  }

  long sessionUs() {
    return sessionUs;
  }

  /** The line table for an editor's file, or null when the capture never touched it. */
  @Nullable
  synchronized Map<Integer, LineTime> forEditorPath(@NotNull String path) {
    String normalized = path.replace('\\', '/');
    if (byEditorPath.containsKey(normalized)) return byEditorPath.get(normalized);
    Map<Integer, LineTime> match = null;
    for (Map.Entry<String, Map<Integer, LineTime>> entry : byFile.entrySet()) {
      if (normalized.endsWith("/" + entry.getKey()) || normalized.equals(entry.getKey())) {
        match = entry.getValue();
        break;
      }
    }
    byEditorPath.put(normalized, match);
    return match;
  }

  /** Tracy captures: exact per-function times from one close-ordered scan of the store. */
  @NotNull
  static HaxeLineTimes fromStore(@NotNull HxtZoneStore store) throws IOException {
    Map<String, Map<Integer, Accumulator>> byFile = new HashMap<>();
    Map<Integer, List<long[]>> pendingByThread = new HashMap<>();
    store.scanZones(-1, 0, Long.MAX_VALUE, 0, (threadId, depth, startNs, endNs, location) -> {
      // a zone's children closed just before it: their durations are
      // pending one level deeper (same fold as the call-tree trie)
      List<long[]> pending = pendingByThread.computeIfAbsent(threadId, id -> new ArrayList<>());
      while (pending.size() <= depth + 1) pending.add(new long[1]);
      long durationNs = endNs - startNs;
      long selfNs = Math.max(durationNs - pending.get(depth + 1)[0], 0);
      pending.get(depth + 1)[0] = 0;
      pending.get(depth)[0] += durationNs;

      if (location.file().isEmpty() || location.line() <= 0) return;
      Accumulator line = byFile
        .computeIfAbsent(location.file().replace('\\', '/'), file -> new HashMap<>())
        .computeIfAbsent(location.line(), key -> new Accumulator());
      line.totalNs += durationNs;
      line.selfNs += selfNs;
    });
    return new HaxeLineTimes(freeze(byFile, Map.of()), store.session().durationNs() / 1000);
  }

  /**
   * Sampled captures, attributed like the Java hints: every frame's line is
   * a call site inside that frame's own method, the leaf line gets the self
   * time, and each line remembers its method and the method's total. The
   * hints are SOURCE-level while HL compiles a generic into one function
   * per type argument (`ObjectPool_geom_Point.get`), so methods are keyed
   * by file + displayed name — a line's "% of method" denominator covers
   * every specialization of the source method, never just its own.
   */
  @NotNull
  static HaxeLineTimes fromSnapshot(@NotNull ProfilerSnapshot snapshot) {
    long periodNs = HaxeSamplingProfilerData.samplePeriodUs(snapshot) * 1000;
    Map<String, Map<Integer, Accumulator>> byFile = new HashMap<>();
    Map<String, Long> methodTotalsNs = new HashMap<>();
    long sessionNs = 0;
    Set<String> chargedLines = new HashSet<>();
    Set<String> chargedMethods = new HashSet<>();
    for (StackSample sample : snapshot.samples()) {
      long timeNs = sample.weight() * periodNs;
      sessionNs += timeNs;
      chargedLines.clear();
      chargedMethods.clear();
      List<StackFrame> frames = sample.frames();
      for (int i = 0; i < frames.size(); i++) {
        StackFrame frame = frames.get(i);
        if (frame.file() == null || frame.line() <= 0) continue;
        String file = frame.file().replace('\\', '/');
        String methodName = displayMethodName(frame.symbol());
        // recursion (and same-line siblings across specializations) charge
        // a method once per sample, not once per frame
        if (chargedMethods.add(file + "#" + methodName)) {
          methodTotalsNs.merge(file + "#" + methodName, timeNs, Long::sum);
        }
        if (!chargedLines.add(file + ":" + frame.line())) continue;
        Accumulator line = byFile
          .computeIfAbsent(file, key -> new HashMap<>())
          .computeIfAbsent(frame.line(), key -> new Accumulator());
        line.totalNs += timeNs;
        line.enclosing = methodName;
        if (i == frames.size() - 1) line.selfNs += timeNs;
      }
    }
    return new HaxeLineTimes(freeze(byFile, methodTotalsNs), sessionNs / 1000);
  }

  /**
   * The symbol's last name segment, extended left across purely numeric
   * segments so a closure ("Main.setup.2") reads "setup.2" rather than a
   * bare "2" that names nothing. This is both the tooltip text and (with
   * the file) the method-total key, so the shown name and the shown share
   * always describe the same thing.
   */
  static String displayMethodName(String symbol) {
    // split on dots - HL symbols are Class.method with one numeric segment
    // appended per closure nesting level
    String[] segments = symbol.split("\\.");
    int first = segments.length - 1;
    while (first > 0 && segments[first].chars().allMatch(Character::isDigit)) first--;
    return String.join(".", Arrays.asList(segments).subList(first, segments.length));
  }

  /** Converts to µs once, drops sub-µs lines, and resolves each line's enclosing-method total. */
  private static Map<String, Map<Integer, LineTime>> freeze(Map<String, Map<Integer, Accumulator>> byFile,
                                                            Map<String, Long> methodTotalsNs) {
    Map<String, Map<Integer, LineTime>> frozen = new HashMap<>();
    byFile.forEach((file, lines) -> {
      Map<Integer, LineTime> frozenLines = new HashMap<>();
      lines.forEach((line, accumulator) -> {
        long totalUs = accumulator.totalNs / 1000;
        if (totalUs <= 0) return;
        long enclosingTotalUs = accumulator.enclosing == null
                                ? 0
                                : methodTotalsNs.getOrDefault(file + "#" + accumulator.enclosing, 0L) / 1000;
        frozenLines.put(line, new LineTime(totalUs, accumulator.selfNs / 1000,
                                           accumulator.enclosing, enclosingTotalUs));
      });
      if (!frozenLines.isEmpty()) frozen.put(file, Map.copyOf(frozenLines));
    });
    return Map.copyOf(frozen);
  }

  private static final class Accumulator {
    long totalNs;
    long selfNs;
    @Nullable String enclosing;
  }
}
