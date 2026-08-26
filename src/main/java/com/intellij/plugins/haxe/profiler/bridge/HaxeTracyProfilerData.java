package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneStore;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import com.intellij.profiler.DummyCallTreeBuilder;
import com.intellij.profiler.api.BaseCallStackElement;
import com.intellij.profiler.api.CallTreeBuildingData;
import com.intellij.profiler.api.MultipleCallTreesProfilerData;
import com.intellij.profiler.api.ProfilerData;
import com.intellij.profiler.model.ThreadInfo;
import com.intellij.profiler.ui.MainCallTreeDataComponent;
import com.intellij.ui.tabs.TabInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tracy zone capture for the IU profiler views: the standard tabs with
 * two states — EXACT self times (a zone's duration minus its direct
 * children — measured, not sampled; kept in microseconds) and invocation
 * counts — plus the Call Chart over the same zones. Wraps a
 * {@link MultipleCallTreesProfilerData} rather than subclassing (the
 * sampling hierarchy is sealed). The capture streams FROM ITS FILE — the
 * store's close-ordered scan (per thread a post-order walk) folds
 * bottom-up into a path trie, and the platform builders get one addStack
 * per DISTINCT path (lossless: their nodes carry only an accumulated
 * value, no counts).
 */
public final class HaxeTracyProfilerData implements ProfilerData {

  private final HxtZoneStore store;
  private final MultipleCallTreesProfilerData trees;

  private HaxeTracyProfilerData(MultipleCallTreesProfilerData trees, HxtZoneStore store) {
    this.trees = trees;
    this.store = store;
  }

  /** The capture's disk store — the gutter hints aggregate their line times from it. */
  @NotNull
  public HxtZoneStore store() {
    return store;
  }

  @NotNull
  public static HaxeTracyProfilerData from(@NotNull HxtZoneStore store) throws IOException {
    Map<TracySourceLocation, HaxeCallStackElement> elements = new HashMap<>();
    Map<Integer, ThreadAggregation> byThread = new HashMap<>();
    store.scanZones(-1, 0, Long.MAX_VALUE, 0, (threadId, depth, startNs, endNs, location) -> {
      ThreadAggregation aggregation = byThread.computeIfAbsent(threadId, id -> new ThreadAggregation());
      HaxeCallStackElement element = elements.computeIfAbsent(location, HaxeTracyProfilerData::toElement);
      aggregation.close(depth, endNs - startNs, element);
    });

    DummyCallTreeBuilder<BaseCallStackElement> timeBuilder = new DummyCallTreeBuilder<>();
    timeBuilder.setMetric(HaxeValueMetrics.TIME_MICROSECONDS);
    DummyCallTreeBuilder<BaseCallStackElement> callsBuilder = new DummyCallTreeBuilder<>();
    callsBuilder.setMetric(HaxeValueMetrics.INVOCATIONS);
    for (HxtZoneStore.ThreadEntry entry : store.threads()) {
      ThreadAggregation aggregation = byThread.get(entry.id());
      if (aggregation == null) continue;
      ThreadInfo thread = new HaxeSamplingProfilerData.HaxeProfilerThreadInfo(entry.name(),
                                                                              Integer.toUnsignedString(entry.id()));
      emit(timeBuilder, callsBuilder, thread, aggregation.roots(), new ArrayList<>());
    }

    CallTreeBuildingData timeTree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.name"),
                                                            new HaxeCallStackElementRenderer(),
                                                            timeBuilder,
                                                            "haxe.tracy.cpu");
    CallTreeBuildingData callsTree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.invocations"),
                                                             new HaxeCallStackElementRenderer(),
                                                             callsBuilder,
                                                             "haxe.tracy.invocations");
    return new HaxeTracyProfilerData(new MultipleCallTreesProfilerData(List.of(timeTree, callsTree)), store);
  }

  /** Depth-first over the trie: one addStack per path and builder; a 300 ns path still visible as 1 µs. */
  private static void emit(DummyCallTreeBuilder<BaseCallStackElement> timeBuilder,
                           DummyCallTreeBuilder<BaseCallStackElement> callsBuilder,
                           ThreadInfo thread,
                           Map<HaxeCallStackElement, PathNode> nodes,
                           List<BaseCallStackElement> path) {
    nodes.forEach((element, node) -> {
      path.add(element);
      List<BaseCallStackElement> frozenPath = List.copyOf(path);
      timeBuilder.addStack(thread, frozenPath, Math.max(node.selfNs / 1000, 1));
      callsBuilder.addStack(thread, frozenPath, node.invocations);
      emit(timeBuilder, callsBuilder, thread, node.children, path);
      path.remove(path.size() - 1);
    });
  }

  private static HaxeCallStackElement toElement(TracySourceLocation location) {
    String file = location.file().isEmpty() ? null : location.file();
    return new HaxeCallStackElement(location.function(), file, location.line());
  }

  @Override
  public boolean isEmpty() {
    return store.zoneCount() == 0;
  }

  /** The standard tabs plus our Call Chart (non-closable, not auto-selected, no event-state controller). */
  @Override
  public @NotNull JComponent doCreateTopLevelComponent(@NotNull Project project, @NotNull Disposable parent) {
    MainCallTreeDataComponent main = new MainCallTreeDataComponent(project, trees, parent, null, false);
    if (store.zoneCount() > 0) {
      TabInfo callChartTab = new TabInfo(HaxeCallChartTab.create(project, store));
      callChartTab.setText(HaxeProfilerBundle.message("haxe.profiler.callchart.tab"));
      main.addTab(callChartTab, false, false, false);
    }
    return main;
  }

  /**
   * Folds one thread's close-ordered zone stream into the trie. A zone at
   * depth d closes AFTER all its children (depth d+1), so the aggregated
   * subtrees pending at d+1 are exactly its children: they fold under its
   * path node, and its self time is its duration minus their total.
   */
  private static final class ThreadAggregation {
    private final List<Level> levels = new ArrayList<>();

    void close(int depth, long durationNs, HaxeCallStackElement element) {
      Level mine = level(depth);
      Level children = level(depth + 1);
      PathNode node = mine.nodes.computeIfAbsent(element, key -> new PathNode());
      node.selfNs += Math.max(durationNs - children.durationNs, 0);
      node.invocations++;
      mergeChildren(node, children.nodes);
      children.nodes = new HashMap<>();
      children.durationNs = 0;
      mine.durationNs += durationNs;
    }

    Map<HaxeCallStackElement, PathNode> roots() {
      return level(0).nodes;
    }

    private Level level(int depth) {
      while (levels.size() <= depth) {
        levels.add(new Level());
      }
      return levels.get(depth);
    }

    private static void mergeChildren(PathNode into, Map<HaxeCallStackElement, PathNode> children) {
      children.forEach((element, child) -> {
        PathNode existing = into.children.get(element);
        if (existing == null) {
          into.children.put(element, child);
        }
        else {
          existing.selfNs += child.selfNs;
          existing.invocations += child.invocations;
          mergeChildren(existing, child.children);
        }
      });
    }

    /** Subtrees closed at one depth, waiting for their parent to close. */
    private static final class Level {
      Map<HaxeCallStackElement, PathNode> nodes = new HashMap<>();
      long durationNs;
    }
  }

  /** One distinct call path: accumulated self time and how many zones ran there. */
  private static final class PathNode {
    final Map<HaxeCallStackElement, PathNode> children = new HashMap<>();
    long selfNs;
    long invocations;
  }
}
