package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import com.intellij.profiler.DummyCallTreeBuilder;
import com.intellij.profiler.api.BaseCallStackElement;
import com.intellij.profiler.api.CallTreeBuildingData;
import com.intellij.profiler.api.SingleCallTreeProfilerData;
import com.intellij.profiler.model.ThreadInfo;
import com.intellij.profiler.ui.MainCallTreeDataComponent;
import com.intellij.ui.tabs.TabInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Haxe profiling capture for the IU profiler views: the standard
 * flamegraph/call-tree/method-list tabs from the sample-built call tree
 * (via the {@link SingleCallTreeProfilerData} base — the sealed
 * {@code SamplingProfilerData} hierarchy is only enterable through it), plus
 * the Call Chart tab built from the same snapshot's time data.
 */
// TODO: range selection on the timeline filtering the call tree to [t1,t2]
//       (rebuild a DummyCallTreeBuilder from the samples inside the range).
public final class HaxeSamplingProfilerData extends SingleCallTreeProfilerData {

  /** Synthetic leaf frame of samples taken inside a collector pause. */
  private static final String GC_SYMBOL = "GC Major";

  private final ProfilerSnapshot snapshot;

  private HaxeSamplingProfilerData(CallTreeBuildingData tree, ProfilerSnapshot snapshot) {
    super(tree);
    this.snapshot = snapshot;
  }

  @NotNull
  public static HaxeSamplingProfilerData from(@NotNull ProfilerSnapshot snapshot) {
    Map<Integer, ThreadInfo> threads = new HashMap<>();
    for (ProfilerThread thread : snapshot.threads()) {
      threads.put(thread.id(), new HaxeProfilerThreadInfo(thread.name(), String.valueOf(thread.id())));
    }

    // interned so identical frames merge into one tree node per thread
    Map<StackFrame, HaxeCallStackElement> elements = new HashMap<>();
    HaxeCallStackElement inGc = new HaxeCallStackElement(GC_SYMBOL, null, StackFrame.NO_LINE);
    DummyCallTreeBuilder<BaseCallStackElement> builder = new DummyCallTreeBuilder<>();
    for (StackSample sample : snapshot.samples()) {
      List<BaseCallStackElement> stack = new ArrayList<>(sample.frames().size() + 1);
      for (StackFrame frame : sample.frames()) {
        stack.add(elements.computeIfAbsent(frame, HaxeSamplingProfilerData::toElement));
      }
      if (sample.inGc()) {
        stack.add(inGc);
      }
      ThreadInfo thread = threads.computeIfAbsent(sample.threadId(),
                                                  id -> new HaxeProfilerThreadInfo("Thread " + id, String.valueOf(id)));
      builder.addStack(thread, stack, sample.weight());
    }

    CallTreeBuildingData tree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.name"),
                                                        new HaxeCallStackElementRenderer(),
                                                        builder,
                                                        "haxe.hashlink.cpu");
    return new HaxeSamplingProfilerData(tree, snapshot);
  }

  @NotNull
  private static HaxeCallStackElement toElement(StackFrame frame) {
    return new HaxeCallStackElement(frame.symbol(), frame.file(), frame.line());
  }

  /** The standard tabs plus our Call Chart (non-closable, not auto-selected, no event-state controller). */
  @Override
  public @NotNull JComponent doCreateTopLevelComponent(@NotNull Project project, @NotNull Disposable parent) {
    MainCallTreeDataComponent main = new MainCallTreeDataComponent(project, this, parent, null, false);
    if (!snapshot.samples().isEmpty()) {
      TabInfo callChartTab = new TabInfo(HaxeCallChartTab.create(project, snapshot));
      callChartTab.setText(HaxeProfilerBundle.message("haxe.profiler.callchart.tab"));
      main.addTab(callChartTab, false, false, false);
    }
    return main;
  }

  record HaxeProfilerThreadInfo(@NotNull String name, @NotNull String nativeId) implements ThreadInfo {
    @Override
    public @NotNull String getName() {
      return name;
    }

    @Override
    public @NotNull String getNativeId() {
      return nativeId;
    }
  }
}
