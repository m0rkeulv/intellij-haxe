package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracySourceLocation;
import com.intellij.plugins.haxe.profiler.tracy.TracyZone;
import com.intellij.profiler.DummyCallTreeBuilder;
import com.intellij.profiler.api.BaseCallStackElement;
import com.intellij.profiler.api.CallTreeBuildingData;
import com.intellij.profiler.api.SingleCallTreeProfilerData;
import com.intellij.profiler.model.ThreadInfo;
import com.intellij.profiler.ui.MainCallTreeDataComponent;
import com.intellij.ui.tabs.TabInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tracy zone capture for the IU profiler views: the standard tabs from a
 * call tree built with EXACT self times (a zone's duration minus its direct
 * children — measured, not sampled; weights in microseconds), plus the Call
 * Chart over the same zones. Zone locations carry file and line, so
 * navigation lands precisely.
 */
public final class HaxeTracyProfilerData extends SingleCallTreeProfilerData {

  private final TracySession session;

  private HaxeTracyProfilerData(CallTreeBuildingData tree, TracySession session) {
    super(tree);
    this.session = session;
  }

  @NotNull
  public static HaxeTracyProfilerData from(@NotNull TracySession session) {
    Map<Integer, ThreadInfo> threads = new HashMap<>();
    Map<TracySourceLocation, HaxeCallStackElement> elements = new HashMap<>();
    DummyCallTreeBuilder<BaseCallStackElement> builder = new DummyCallTreeBuilder<>();

    Map<Integer, Deque<OpenZone>> stacks = new HashMap<>();
    for (TracyZone zone : session.zones()) {
      Deque<OpenZone> stack = stacks.computeIfAbsent(zone.threadId(), thread -> new ArrayDeque<>());
      while (!stack.isEmpty() && zone.startNs() >= stack.peek().zone.endNs()) {
        close(builder, threads, session, stack);
      }
      stack.push(new OpenZone(zone, pathOf(stack, elements, zone)));
    }
    for (Deque<OpenZone> stack : stacks.values()) {
      while (!stack.isEmpty()) {
        close(builder, threads, session, stack);
      }
    }

    CallTreeBuildingData tree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.name"),
                                                        new HaxeCallStackElementRenderer(),
                                                        builder,
                                                        "haxe.tracy.cpu");
    return new HaxeTracyProfilerData(tree, session);
  }

  /** Pops the finished zone and records its EXACT self time (µs) under its call path. */
  private static void close(DummyCallTreeBuilder<BaseCallStackElement> builder,
                            Map<Integer, ThreadInfo> threads,
                            TracySession session,
                            Deque<OpenZone> stack) {
    OpenZone closed = stack.pop();
    long selfNs = closed.zone.durationNs() - closed.childrenNs;
    if (!stack.isEmpty()) {
      stack.peek().childrenNs += closed.zone.durationNs();
    }
    ThreadInfo thread = threads.computeIfAbsent(closed.zone.threadId(), id -> threadInfo(session, id));
    builder.addStack(thread, closed.path, Math.max(selfNs / 1000, 1));
  }

  private static List<BaseCallStackElement> pathOf(Deque<OpenZone> stack,
                                                   Map<TracySourceLocation, HaxeCallStackElement> elements,
                                                   TracyZone zone) {
    HaxeCallStackElement element = elements.computeIfAbsent(zone.location(), HaxeTracyProfilerData::toElement);
    List<BaseCallStackElement> path = new ArrayList<>(stack.size() + 1);
    if (!stack.isEmpty()) {
      path.addAll(stack.peek().path);
    }
    path.add(element);
    return path;
  }

  private static HaxeCallStackElement toElement(TracySourceLocation location) {
    String file = location.file().isEmpty() ? null : location.file();
    return new HaxeCallStackElement(location.function(), file, location.line());
  }

  private static ThreadInfo threadInfo(TracySession session, int threadId) {
    String name = session.threadNames().getOrDefault(threadId, "Thread " + Integer.toUnsignedString(threadId));
    return new HaxeSamplingProfilerData.HaxeProfilerThreadInfo(name, Integer.toUnsignedString(threadId));
  }

  /** The standard tabs plus our Call Chart (non-closable, not auto-selected, no event-state controller). */
  @Override
  public @NotNull JComponent doCreateTopLevelComponent(@NotNull Project project, @NotNull Disposable parent) {
    MainCallTreeDataComponent main = new MainCallTreeDataComponent(project, this, parent, null, false);
    if (!session.zones().isEmpty()) {
      TabInfo callChartTab = new TabInfo(HaxeCallChartTab.create(project, session));
      callChartTab.setText(HaxeProfilerBundle.message("haxe.profiler.callchart.tab"));
      main.addTab(callChartTab, false, false, false);
    }
    return main;
  }

  private static final class OpenZone {
    final TracyZone zone;
    final List<BaseCallStackElement> path;
    long childrenNs;

    OpenZone(TracyZone zone, List<BaseCallStackElement> path) {
      this.zone = zone;
      this.path = path;
    }
  }
}
