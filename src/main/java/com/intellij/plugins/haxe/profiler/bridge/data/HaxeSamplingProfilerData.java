package com.intellij.plugins.haxe.profiler.bridge.data;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.HaxeLiveCaptures;
import com.intellij.plugins.haxe.profiler.bridge.chart.HaxeCallChartTab;
import com.intellij.plugins.haxe.profiler.hxt.HxtSessionTranslator;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import com.intellij.profiler.DummyCallTreeBuilder;
import com.intellij.profiler.api.BaseCallStackElement;
import com.intellij.profiler.api.CallTreeBuildingData;
import com.intellij.profiler.api.MultipleCallTreesProfilerData;
import com.intellij.profiler.api.ProfilerData;
import com.intellij.profiler.model.ThreadInfo;
import com.intellij.profiler.ui.MainCallTreeDataComponent;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/**
 * A parsed Haxe profiling capture for the IU profiler views: the standard
 * flamegraph/call-tree/method-list tabs with two states — CPU time
 * (samples × the capture's tick period, in microseconds) and raw sample
 * counts — plus the Call Chart tab built from the same snapshot's time
 * data. Wraps a {@link MultipleCallTreesProfilerData} rather than
 * subclassing (the sampling hierarchy is sealed).
 */
// TODO: range selection on the timeline filtering the call tree to [t1,t2]
//       (rebuild a DummyCallTreeBuilder from the samples inside the range).
public final class HaxeSamplingProfilerData implements ProfilerData {

  /** Synthetic leaf frame of samples taken inside a collector pause. */
  private static final String GC_SYMBOL = "GC Major";

  private final ProfilerSnapshot snapshot;
  private final MultipleCallTreesProfilerData trees;
  /** The session file this data was parsed from; a still-live capture of it renders the self-refreshing view. */
  private @Nullable Path sourceFile;

  private HaxeSamplingProfilerData(MultipleCallTreesProfilerData trees, ProfilerSnapshot snapshot) {
    this.trees = trees;
    this.snapshot = snapshot;
  }

  /** The parsed capture — the gutter hints aggregate their line times from it. */
  @NotNull
  public ProfilerSnapshot snapshot() {
    return snapshot;
  }

  @NotNull
  public static HaxeSamplingProfilerData from(@NotNull ProfilerSnapshot snapshot, @Nullable Path sourceFile) {
    HaxeSamplingProfilerData data = from(snapshot);
    data.sourceFile = sourceFile;
    return data;
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
    DummyCallTreeBuilder<BaseCallStackElement> timeBuilder = new DummyCallTreeBuilder<>();
    timeBuilder.setMetric(HaxeValueMetrics.TIME_MICROSECONDS);
    DummyCallTreeBuilder<BaseCallStackElement> samplesBuilder = new DummyCallTreeBuilder<>();
    long periodUs = samplePeriodUs(snapshot);
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
      timeBuilder.addStack(thread, stack, sample.weight() * periodUs);
      samplesBuilder.addStack(thread, stack, sample.weight());
    }

    CallTreeBuildingData timeTree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.name"),
                                                            new HaxeCallStackElementRenderer(),
                                                            timeBuilder,
                                                            "haxe.hashlink.cpu");
    CallTreeBuildingData samplesTree = new CallTreeBuildingData(HaxeProfilerBundle.message("haxe.profiler.tree.samples"),
                                                               new HaxeCallStackElementRenderer(),
                                                               samplesBuilder,
                                                               "haxe.hashlink.samples");
    return new HaxeSamplingProfilerData(new MultipleCallTreesProfilerData(List.of(timeTree, samplesTree)), snapshot);
  }

  /** One sample's worth of time; the sample rate is validated at parse time. */
  public static long samplePeriodUs(ProfilerSnapshot snapshot) {
    return Math.max(1_000_000L / Math.max(snapshot.samplesPerSecond(), 1), 1);
  }

  @NotNull
  private static HaxeCallStackElement toElement(StackFrame frame) {
    return new HaxeCallStackElement(frame.symbol(), frame.file(), frame.line());
  }

  @Override
  public boolean isEmpty() {
    return snapshot.samples().isEmpty();
  }

  /** The standard tabs plus our Call Chart (non-closable, not auto-selected, no event-state controller). */
  @Override
  public @NotNull JComponent doCreateTopLevelComponent(@NotNull Project project, @NotNull Disposable parent) {
    HaxeLiveCaptures.Entry live = sourceFile == null ? null : HaxeLiveCaptures.find(sourceFile);
    if (live != null && live.isLive()) {
      return liveComponent(project, parent, live);
    }
    return buildComponent(project, parent, null, false, null);
  }

  /**
   * The live phase: tree tabs from the partial parse, the Call Chart
   * self-refreshing off the growing file; when the capture completes the
   * WHOLE component rebuilds once from the final file, so the tree tabs
   * stop being an early partial snapshot.
   */
  private JComponent liveComponent(Project project, Disposable parent, HaxeLiveCaptures.Entry live) {
    BorderLayoutPanel wrapper = new BorderLayoutPanel();
    JComponent[] liveChart = new JComponent[1];
    wrapper.addToCenter(buildComponent(project, parent, live, false, liveChart));
    Path file = sourceFile;
    live.onCompletion(() -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
      ProfilerSnapshot finalSnapshot;
      // live registrations only come from v1 (.hxtsession) receivers
      try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
        finalSnapshot = HxtSessionTranslator.translate(in);
      }
      catch (IOException e) {
        return; // the live chart already shows the last good refresh
      }
      HaxeSamplingProfilerData finalData = from(finalSnapshot, file);
      ApplicationManager.getApplication().invokeLater(() -> {
        // the rebuild must not steal the user's place: a chart being
        // watched stays the selected tab afterwards
        boolean chartShowing = liveChart[0] != null && liveChart[0].isShowing();
        wrapper.removeAll();
        wrapper.addToCenter(finalData.buildComponent(project, parent, null, chartShowing, null));
        wrapper.revalidate();
        wrapper.repaint();
      });
    }));
    return wrapper;
  }

  private JComponent buildComponent(Project project, Disposable parent, HaxeLiveCaptures.@Nullable Entry live,
                                    boolean selectChart, JComponent @Nullable [] chartOut) {
    MainCallTreeDataComponent main = new MainCallTreeDataComponent(project, trees, parent, null, false);
    // a live tab renders even before the first samples land - it fills itself
    if (live != null || !snapshot.samples().isEmpty()) {
      JComponent chart = live != null
                         ? HaxeCallChartTab.createLive(project, snapshot, sourceFile, live, parent)
                         : HaxeCallChartTab.create(project, snapshot);
      if (chartOut != null) chartOut[0] = chart;
      TabInfo callChartTab = new TabInfo(chart);
      callChartTab.setText(HaxeProfilerBundle.message("haxe.profiler.callchart.tab"));
      main.addTab(callChartTab, false, selectChart, false);
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
