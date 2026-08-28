package com.intellij.plugins.haxe.profiler.bridge.chart;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.popup.IconButton;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.HaxeLiveCaptures;
import com.intellij.plugins.haxe.profiler.bridge.HaxeProfilerFormats;
import com.intellij.plugins.haxe.profiler.bridge.chart.HaxeCallChartPanel.MarkerLane;
import com.intellij.plugins.haxe.profiler.bridge.data.HaxeCallStackElement;
import com.intellij.plugins.haxe.profiler.hxt.HxtLiveSession;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneStore;
import com.intellij.plugins.haxe.profiler.model.ProfilerEvent;
import com.intellij.plugins.haxe.profiler.model.ProfilerMemorySample;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.StackSample;
import com.intellij.plugins.haxe.profiler.model.TimelineEvent;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.FlameNode;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.UsSpan;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracyZone;
import com.intellij.plugins.haxe.profiler.tracy.TracyZoneTrees;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;

/**
 * The Call Chart tab: one thread's capture explorable over a time axis, the
 * concept Chrome DevTools and the Android profiler render
 * ({@link HaxeCallChartPanel} over a per-thread flame tree). Two data
 * sources feed the same UI: sampled snapshots (reconstructed runs) and
 * tracy zone sessions (exact measured intervals). A thread picker on top
 * switches the charted thread; clicking a run fills the detail panel on
 * the right (collapsible to a thin strip); the toolbar on the left zooms
 * and opens the Configure View dialog governing lane visibility.
 */
public final class HaxeCallChartTab {

  /** Deeper frames fold into their ancestor; the flamegraph tab still shows full depth. */
  private static final int MAX_DEPTH = 64;

  private static final Color GC_LANE = new JBColor(0xD66A6A, 0xA14A4A);
  private static final Color FRAME_LANE_EVEN = new JBColor(0x9CB8D6, 0x53687D);
  private static final Color FRAME_LANE_ODD = new JBColor(0xC4D4E4, 0x3E4E5E);
  private static final Color[] MEMORY_LANE_COLORS = {
    new JBColor(0x7FB58A, 0x4E7157),
    new JBColor(0x8AA6C8, 0x50647C)};
  private static final Color CPU_LANE = new JBColor(0xC79A50, 0x8A6D3F);
  private static final Color GPU_MEMORY_LANE = new JBColor(0x5FA3A3, 0x487878);
  private static final Color GPU_LOAD_LANE = new JBColor(0x9B7FBF, 0x6A5688);
  /** How many breakdown entries a frame's details list before folding the rest into "other". */
  private static final int BREAKDOWN_LINES = 8;
  /** Buckets of the derived thread-activity series across the session. */
  private static final int ACTIVITY_BUCKETS = 512;

  /** The per-thread chart inputs one capture kind provides. */
  private interface ChartData {
    List<ProfilerThread> threads();

    /**
     * The thread's tree for [fromUs, toUs] with zones under
     * {@code minDurationUs} dropped; sources without windowed loading
     * ignore the bounds and return the whole capture. The returned root
     * always spans the whole session, so the chart's axis stays put.
     */
    FlameNode treeFor(int threadId, long fromUs, long toUs, long minDurationUs);

    /** True when {@link #treeFor} serves real windows and zooming should reload. */
    default boolean windowedLoads() {
      return false;
    }

    /** The capture length; only meaningful for windowed sources. */
    default long durationUs() {
      return 0;
    }

    /** Coarse top-level activity for the minimap when the capture has no frames. */
    default List<UsSpan> coarseActivity(int threadId) {
      return List.of();
    }

    /** Instant marks for the events row — any lane with a user-event API can supply them. */
    default List<TimelineEvent> events() {
      return List.of();
    }

    GcSpans gcSpansFor(int threadId);

    List<UsSpan> frameSpansFor(int threadId);

    /** The category's value-over-time series by name; categories a capture cannot fill honestly stay empty. */
    default Map<String, List<TracySession.PlotPoint>> curveSeries(CurveCategory category, int threadId) {
      return Map.of();
    }

    /**
     * A selected frame's time breakdown: top-level work inside the span
     * merged by function, exact per the capture's acquisition — empty when
     * the capture cannot answer without guesswork. May read the capture
     * file; call off the EDT.
     */
    default List<FrameSlice> frameBreakdown(int threadId, UsSpan frame) {
      return List.of();
    }

    /** A small screenshot of the frame for the details panel; null until a lane can supply one. */
    default @Nullable Image frameImage(UsSpan frame) {
      return null;
    }
  }

  /** The curve-lane categories a capture may fill, in display order. */
  enum CurveCategory {
    MEMORY("haxe.profiler.callchart.lane.memory", HaxeCallChartPanel.CurveUnit.BYTES),
    GPU_MEMORY("haxe.profiler.callchart.lane.gpu.memory", HaxeCallChartPanel.CurveUnit.BYTES),
    CPU_LOAD("haxe.profiler.callchart.lane.cpu", HaxeCallChartPanel.CurveUnit.PERCENT),
    GPU_LOAD("haxe.profiler.callchart.lane.gpu.load", HaxeCallChartPanel.CurveUnit.PERCENT);

    final String labelKey;
    final HaxeCallChartPanel.CurveUnit unit;

    CurveCategory(String labelKey, HaxeCallChartPanel.CurveUnit unit) {
      this.labelKey = labelKey;
      this.unit = unit;
    }
  }

  /** One entry of a frame's time breakdown. */
  record FrameSlice(@NotNull String name, long totalUs) {
  }

  /** A GC lane's spans plus how the detail view describes ONE of them. */
  private record GcSpans(@NotNull List<UsSpan> spans, @NotNull String spanKindKey,
                         @Nullable Function<UsSpan, String> spanInfo) {
  }

  private HaxeCallChartTab() {
  }

  @NotNull
  public static JComponent create(@NotNull Project project, @NotNull ProfilerSnapshot snapshot) {
    return create(project, new SnapshotData(snapshot), null, null, null);
  }

  @NotNull
  public static JComponent create(@NotNull Project project, @NotNull HxtZoneStore store) {
    return create(project, new ZoneData(store), null, null, null);
  }

  /**
   * The LIVE form for a still-streaming v1 capture: re-reads the growing
   * session file every second, follows the tail until the user moves the
   * view, refreshes once more when the capture completes.
   */
  @NotNull
  public static JComponent createLive(@NotNull Project project, @NotNull ProfilerSnapshot initial,
                                      @NotNull Path sessionFile, HaxeLiveCaptures.@NotNull Entry live,
                                      @NotNull Disposable parent) {
    return create(project, new SnapshotData(initial), sessionFile, live, parent);
  }

  @NotNull
  private static JComponent create(@NotNull Project project, @NotNull ChartData initialData,
                                   @Nullable Path sessionFile, HaxeLiveCaptures.@Nullable Entry live,
                                   @Nullable Disposable parent) {
    ChartData[] data = {initialData};
    HaxeStackDetailPanel details = new HaxeStackDetailPanel(project);
    LaneState lanes = new LaneState();
    HaxeCallChartPanel chart = new HaxeCallChartPanel(frame -> HaxeCallStackElement.navigateToFrame(project, frame),
                                                      path -> showRunDetails(details, path),
                                                      (lane, span) -> showSpanDetails(details, lanes, lane, span),
                                                      selection -> showCurveDetails(details, selection),
                                                      event -> showEventDetails(details, event));
    HaxeChartViewSettings viewSettings = HaxeChartViewSettings.getInstance(project);
    chart.setViewPreferences(viewSettings.bandOrder(), viewSettings.bandHeights(), viewSettings.collapsedBands(),
                             viewSettings::update);

    HaxeChartMinimapPanel minimap = new HaxeChartMinimapPanel(chart::setView);
    WindowLoader loader = new WindowLoader(initialData, chart);
    // [startUs, visibleUs, wholeSession(0/1)] - the live refresher windows its tree builds to this
    long[] liveView = {0, 0, 1};
    chart.setViewListener((startUs, visibleUs, wholeSession) -> {
      liveView[0] = startUs;
      liveView[1] = visibleUs;
      liveView[2] = wholeSession ? 1 : 0;
      minimap.showWindow(startUs, visibleUs, wholeSession);
      loader.viewChanged(startUs, visibleUs, wholeSession);
    });

    ComboBox<ProfilerThread> threadPicker = new ComboBox<>(initialData.threads().toArray(new ProfilerThread[0]));
    threadPicker.setRenderer(BuilderKt.textListCellRenderer("", ProfilerThread::name));
    Runnable applyThread = () -> {
      ProfilerThread thread = (ProfilerThread)threadPicker.getSelectedItem();
      if (thread != null) {
        long floorUs = data[0].windowedLoads() ? data[0].durationUs() / WindowLoader.RESOLUTION : 0;
        loader.initialize(thread.id(), floorUs);
        FlameNode tree = data[0].treeFor(thread.id(), 0, Long.MAX_VALUE, floorUs);
        chart.setTree(tree);
        loadThreadData(data[0], lanes, thread.id());
        applyLaneDefaults(lanes, viewSettings);
        applyLanes(chart, lanes);
        minimap.setContent(tree.durationUs(), lanes.frameSpans, data[0].coarseActivity(thread.id()));
      }
    };
    ActionListener threadListener = event -> applyThread.run();
    threadPicker.addActionListener(threadListener);
    applyThread.run();

    if (live != null && sessionFile != null) {
      chart.setFollowLive(true);
      installLiveRefresh(sessionFile, live, parent, data, lanes, chart, minimap, loader,
                         threadPicker, threadListener, viewSettings, liveView);
    }

    Runnable configureView = () -> {
      HaxeChartViewDialog dialog = new HaxeChartViewDialog(project, laneSnapshot(lanes), dataDefaults(lanes));
      if (!dialog.showAndGet()) return;
      HaxeChartViewDialog.Lanes chosen = dialog.lanes();
      lanes.showFrames = chosen.frames();
      lanes.showGc = chosen.gc();
      lanes.showEvents = chosen.events();
      lanes.showCalls = chosen.calls();
      lanes.showCurve.putAll(chosen.curves());
      viewSettings.updateLaneVisibility(visibilityMap(lanes));
      applyLanes(chart, lanes);
    };
    chart.setConfigureViewOpener(configureView);

    // the chart's horizontal axis is virtual (it always fills the viewport
    // and scrolls via its own bar); the scroll pane only handles vertical
    JBScrollPane scrollPane = new JBScrollPane(chart,
                                               ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                               ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    BorderLayoutPanel chartArea = new BorderLayoutPanel();
    chartArea.addToTop(minimap);
    chartArea.addToCenter(scrollPane);
    chartArea.addToBottom(chart.createHorizontalScrollBar());

    BorderLayoutPanel chartSide = new BorderLayoutPanel();
    chartSide.addToTop(threadPicker);
    chartSide.addToLeft(chartToolbar(project, chart, configureView));
    chartSide.addToCenter(chartArea);

    OnePixelSplitter splitter = new OnePixelSplitter(false, 0.72f);
    splitter.setHonorComponentsMinimumSize(true);
    splitter.setFirstComponent(chartSide);
    new DetailsCollapse(splitter, details, viewSettings);
    return splitter;
  }

  /** The lane state as one dialog snapshot. */
  private static HaxeChartViewDialog.Lanes laneSnapshot(LaneState lanes) {
    return new HaxeChartViewDialog.Lanes(lanes.showFrames, lanes.showGc, lanes.showEvents, lanes.showCalls,
                                         new EnumMap<>(lanes.showCurve));
  }

  /** The data-driven defaults: a lane with data shown, one without hidden; the call chart always shown. */
  private static HaxeChartViewDialog.Lanes dataDefaults(LaneState lanes) {
    Map<CurveCategory, Boolean> curves = new EnumMap<>(CurveCategory.class);
    for (CurveCategory category : CurveCategory.values()) {
      curves.put(category, !lanes.curves.get(category).isEmpty());
    }
    return new HaxeChartViewDialog.Lanes(!lanes.frameSpans.isEmpty(), !lanes.gcSpans.isEmpty(),
                                         !lanes.events.isEmpty(), true, curves);
  }

  /** Every lane's visibility keyed for {@link HaxeChartViewSettings}. */
  private static Map<String, Boolean> visibilityMap(LaneState lanes) {
    Map<String, Boolean> map = new HashMap<>();
    map.put("frames", lanes.showFrames);
    map.put("gc", lanes.showGc);
    map.put("events", lanes.showEvents);
    map.put("calls", lanes.showCalls);
    for (CurveCategory category : CurveCategory.values()) {
      map.put("curve:" + category.name(), lanes.showCurve.get(category));
    }
    return map;
  }

  /** Sampled captures: reconstructed runs, GC from sample flags (per-frame events as fallback), frames from events. */
  private record SnapshotData(@NotNull ProfilerSnapshot snapshot) implements ChartData {
    @Override
    public List<ProfilerThread> threads() {
      return snapshot.threads();
    }

    @Override
    public FlameNode treeFor(int threadId, long fromUs, long toUs, long minDurationUs) {
      // sampled captures are small - the whole tree loads regardless of window
      return ProfilerTimeline.flameTree(snapshot, threadId, MAX_DEPTH);
    }

    @Override
    public GcSpans gcSpansFor(int threadId) {
      // sample-flagged spans (HL) first; telemetry captures carry GC as
      // per-frame time events instead - stop-the-world, so not per-thread
      List<UsSpan> flagged = ProfilerTimeline.gcSpans(snapshot, threadId);
      if (!flagged.isEmpty()) return new GcSpans(flagged, "haxe.profiler.callchart.span.gc", null);
      return new GcSpans(ProfilerTimeline.gcSpansFromEvents(snapshot), "haxe.profiler.callchart.span.gc.frame", null);
    }

    @Override
    public List<UsSpan> frameSpansFor(int threadId) {
      List<UsSpan> own = ProfilerTimeline.frameSpans(snapshot, threadId);
      if (!own.isEmpty()) return own;
      // the first thread with end-of-frame markers carries the app rhythm
      for (ProfilerThread thread : snapshot.threads()) {
        List<UsSpan> spans = ProfilerTimeline.frameSpans(snapshot, thread.id());
        if (!spans.isEmpty()) return spans;
      }
      return List.of();
    }

    @Override
    public Map<String, List<TracySession.PlotPoint>> curveSeries(CurveCategory category, int threadId) {
      if (category == CurveCategory.CPU_LOAD) return activitySeries(threadId);
      // per-frame heap readings from the collectors (hxcpp telemetry, flash); HL dumps carry none
      if (category != CurveCategory.MEMORY || snapshot.memory().isEmpty()) return Map.of();
      double start = ProfilerTimeline.captureStartSeconds(snapshot);
      List<TracySession.PlotPoint> used = new ArrayList<>();
      List<TracySession.PlotPoint> reserved = new ArrayList<>();
      List<TracySession.PlotPoint> allocated = new ArrayList<>();
      boolean anyReserved = false;
      boolean anyAllocated = false;
      for (ProfilerMemorySample sample : snapshot.memory()) {
        long timeNs = Math.round((sample.time() - start) * 1_000_000_000);
        used.add(new TracySession.PlotPoint(timeNs, sample.usedBytes()));
        reserved.add(new TracySession.PlotPoint(timeNs, sample.reservedBytes()));
        allocated.add(new TracySession.PlotPoint(timeNs, sample.allocatedBytes()));
        anyReserved |= sample.reservedBytes() > 0;
        anyAllocated |= sample.allocatedBytes() > 0;
      }
      // series without a single reading (no reservation figure on flash,
      // allocation tracking off) would chart a dead flat zero
      Map<String, List<TracySession.PlotPoint>> series = new LinkedHashMap<>();
      series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.heap.used"), used);
      if (anyReserved) {
        series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.heap.reserved"), reserved);
      }
      if (anyAllocated) {
        series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.allocated"), allocated);
      }
      return series;
    }

    /**
     * Busy share per bucket from the samples: the fraction of wall time
     * covered by samples whose root is application work — idle roots and
     * uncovered gaps count as idle. The honest sampled stand-in for a CPU
     * curve; a real process-CPU figure needs scheduler data no sampler has.
     */
    private Map<String, List<TracySession.PlotPoint>> activitySeries(int threadId) {
      double start = ProfilerTimeline.captureStartSeconds(snapshot);
      double tickUs = snapshot.samplesPerSecond() > 0 ? 1_000_000.0 / snapshot.samplesPerSecond() : 1;
      long durationUs = 0;
      for (StackSample sample : snapshot.samples()) {
        durationUs = Math.max(durationUs, Math.round((sample.time() - start) * 1_000_000));
      }
      if (durationUs <= 0) return Map.of();

      long bucketUs = Math.max(durationUs / ACTIVITY_BUCKETS, 1_000);
      long[] busy = new long[(int)(durationUs / bucketUs) + 1];
      boolean any = false;
      for (StackSample sample : snapshot.samples()) {
        if (sample.threadId() != threadId) continue;
        if (IDLE_ROOTS.contains(sample.frames().getFirst().symbol())) continue;
        any = true;
        // the weight covers the time BEFORE the sample's tick
        long endUs = Math.round((sample.time() - start) * 1_000_000);
        long startUs = Math.max(endUs - Math.round(sample.weight() * tickUs), 0);
        int first = (int)(startUs / bucketUs);
        int last = (int)Math.min(endUs / bucketUs, busy.length - 1);
        for (int bucket = Math.max(first, 0); bucket <= last; bucket++) {
          long bucketStart = bucket * bucketUs;
          long overlap = Math.min(endUs, bucketStart + bucketUs) - Math.max(startUs, bucketStart);
          if (overlap > 0) busy[bucket] += overlap;
        }
      }
      if (!any) return Map.of();

      List<TracySession.PlotPoint> points = new ArrayList<>(busy.length);
      for (int bucket = 0; bucket < busy.length; bucket++) {
        double percent = Math.min(busy[bucket] * 100.0 / bucketUs, 100.0);
        points.add(new TracySession.PlotPoint(bucket * bucketUs * 1000, percent));
      }
      return Map.of(HaxeProfilerBundle.message("haxe.profiler.callchart.series.thread.activity"), points);
    }

    /**
     * Sample time inside the span merged by {@link #sliceNameOf}: SELF
     * attribution (the executing leaf, or the stack's GC frame), so the
     * shares name the frame's hot methods and the collectors' pseudo-frame
     * activities ([render], [idle]). Each sample COVERS the interval its
     * weight spans (ending at its tick), clipped to the frame — a
     * coalesced idle stretch then spreads honestly over every frame it
     * crosses instead of landing whole on the one holding its endpoint.
     * Sampling-resolution shares, exact bounds unknowable from samples.
     */
    @Override
    public List<FrameSlice> frameBreakdown(int threadId, UsSpan frame) {
      double start = ProfilerTimeline.captureStartSeconds(snapshot);
      double tickUs = snapshot.samplesPerSecond() > 0 ? 1_000_000.0 / snapshot.samplesPerSecond() : 1;
      Map<String, long[]> bySlice = new HashMap<>();
      for (StackSample sample : snapshot.samples()) {
        if (sample.threadId() != threadId) continue;
        long endUs = Math.round((sample.time() - start) * 1_000_000);
        long startUs = endUs - Math.round(sample.weight() * tickUs);
        long overlap = Math.min(endUs, frame.endUs()) - Math.max(startUs, frame.startUs());
        if (overlap <= 0) continue;
        bySlice.computeIfAbsent(sliceNameOf(sample), key -> new long[1])[0] += overlap;
      }
      List<FrameSlice> slices = new ArrayList<>();
      bySlice.forEach((name, us) -> slices.add(new FrameSlice(name, us[0])));
      slices.sort(Comparator.comparingLong(FrameSlice::totalUs).reversed());
      return slices;
    }

    /**
     * The name a sample's share files under: its GC frame when it ran
     * inside the collector — hxcpp's GC entry points sit MID-stack under
     * the allocation site — else its LEAF, the function executing when
     * the tick landed. Self attribution names the frame's hot methods;
     * root slices collapse into one row when everything nests under a
     * single main loop (hxcpp's __hxcpp_main). Pseudo-frames ([idle],
     * [render]) are single-frame stacks, so their leaf IS the root.
     */
    private static String sliceNameOf(StackSample sample) {
      for (StackFrame frame : sample.frames()) {
        if (isGcFrameName(frame.symbol())) return frame.symbol();
      }
      if (sample.inGc()) return "[gc]";
      return sample.frames().getLast().symbol();
    }

    /** Codes 0 and 1 are the reserved frame/GC markers; everything else is the app's own marks. */
    @Override
    public List<TimelineEvent> events() {
      double start = ProfilerTimeline.captureStartSeconds(snapshot);
      List<TimelineEvent> marks = new ArrayList<>();
      for (ProfilerEvent event : snapshot.events()) {
        if (event.code() == ProfilerEvent.FRAME_CODE || event.code() == ProfilerEvent.GC_TIME_CODE) continue;
        String text = event.data().isEmpty()
                      ? HaxeProfilerBundle.message("haxe.profiler.callchart.event.code", event.code())
                      : event.data();
        marks.add(new TimelineEvent(event.threadId(), Math.round((event.time() - start) * 1_000_000_000), text, 0));
      }
      return marks;
    }
  }

  /** Tracy captures: exact zone trees served from the store; GC from the collector's free bursts; frames from marks. */
  private record ZoneData(@NotNull HxtZoneStore store) implements ChartData {

    @Override
    public List<ProfilerThread> threads() {
      return store.threads().stream()
        .map(entry -> new ProfilerThread(entry.id(), entry.name()))
        .toList();
    }

    @Override
    public FlameNode treeFor(int threadId, long fromUs, long toUs, long minDurationUs) {
      long toNs = toUs >= Long.MAX_VALUE / 2000 ? Long.MAX_VALUE : toUs * 1000;
      List<TracyZone> ordered = new ArrayList<>();
      try {
        store.scanZones(threadId, fromUs * 1000, toNs, minDurationUs * 1000,
                        (thread, depth, startNs, endNs, location) ->
                          ordered.add(new TracyZone(thread, startNs, endNs, location)));
      }
      catch (IOException e) {
        Logger.getInstance(HaxeCallChartTab.class).warn("could not read the zone capture", e);
        return TracyZoneTrees.treeFromOrdered(List.of(), store.session().durationNs());
      }
      ordered.sort(Comparator.comparingLong(TracyZone::startNs));
      return TracyZoneTrees.treeFromOrdered(ordered, store.session().durationNs());
    }

    @Override
    public boolean windowedLoads() {
      return true;
    }

    @Override
    public long durationUs() {
      return store.session().durationNs() / 1000;
    }

    @Override
    public List<UsSpan> coarseActivity(int threadId) {
      // top-level zones at minimap resolution - the strip's silhouette
      long floorNs = store.session().durationNs() / 2000;
      List<UsSpan> spans = new ArrayList<>();
      try {
        store.scanZones(threadId, 0, Long.MAX_VALUE, floorNs, (thread, depth, startNs, endNs, location) -> {
          if (depth == 0) {
            long startUs = startNs / 1000;
            spans.add(new UsSpan(startUs, Math.max(endNs / 1000, startUs + 1)));
          }
        });
      }
      catch (IOException e) {
        Logger.getInstance(HaxeCallChartTab.class).warn("could not read the zone capture", e);
        return List.of();
      }
      spans.sort(Comparator.comparingLong(UsSpan::startUs));
      return spans;
    }

    @Override
    public GcSpans gcSpansFor(int threadId) {
      // upstream emits no GC zones (the hooks are commented out); the
      // collector's free bursts stand in, and carry the reclaim numbers
      Map<UsSpan, String> reclaimBySpan = new HashMap<>();
      List<UsSpan> spans = new ArrayList<>();
      for (TracySession.GcSweep sweep : store.session().gcSweeps()) {
        long startUs = sweep.startNs() / 1000;
        UsSpan span = new UsSpan(startUs, Math.max(sweep.endNs() / 1000, startUs + 1));
        spans.add(span);
        String freed = HaxeProfilerFormats.formatBytes(sweep.freedBytes());
        reclaimBySpan.put(span, HaxeProfilerBundle.message("haxe.profiler.callchart.gc.freed",
                                                           freed, sweep.freedObjects()));
      }
      return new GcSpans(spans, "haxe.profiler.callchart.span.gc.sweep", reclaimBySpan::get);
    }

    @Override
    public List<UsSpan> frameSpansFor(int threadId) {
      return TracyZoneTrees.frameSpans(store.session());
    }

    @Override
    public Map<String, List<TracySession.PlotPoint>> curveSeries(CurveCategory category, int threadId) {
      return switch (category) {
        case MEMORY -> new TreeMap<>(store.session().memoryCurves());
        case CPU_LOAD -> cpuSeries(threadId);
        case GPU_MEMORY -> gpuPlots(true);
        case GPU_LOAD -> gpuPlots(false);
      };
    }

    /**
     * Exact percent series, narrowest scope first: the charted thread's
     * activity (the share of wall time inside instrumented code, folded
     * from the top-level zones), the whole process's scheduler CPU use
     * (percent of one core, present only when the run was elevated for
     * system tracing) and tracy's SysTime — which is the WHOLE SYSTEM's
     * usage across all cores and processes, so it is labeled as such.
     */
    private Map<String, List<TracySession.PlotPoint>> cpuSeries(int threadId) {
      Map<String, List<TracySession.PlotPoint>> series = new LinkedHashMap<>();
      List<TracySession.PlotPoint> activity = threadActivity(threadId);
      if (!activity.isEmpty()) {
        series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.thread.activity"), activity);
      }
      if (!store.session().processCpu().isEmpty()) {
        series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.process.cpu"), store.session().processCpu());
      }
      if (!store.session().cpuUsage().isEmpty()) {
        series.put(HaxeProfilerBundle.message("haxe.profiler.callchart.series.system.cpu"), store.session().cpuUsage());
      }
      return series;
    }

    /** Busy percent per bucket from the exact top-level zones, at the minimap's resolution floor. */
    private List<TracySession.PlotPoint> threadActivity(int threadId) {
      long durationNs = store.session().durationNs();
      if (durationNs <= 0) return List.of();
      long bucketNs = Math.max(durationNs / ACTIVITY_BUCKETS, 1_000_000);
      long[] busy = new long[(int)(durationNs / bucketNs) + 1];
      try {
        store.scanZones(threadId, 0, Long.MAX_VALUE, bucketNs / 100, (thread, depth, startNs, endNs, location) -> {
          if (depth != 0) return;
          int first = (int)(startNs / bucketNs);
          int last = (int)Math.min(endNs / bucketNs, busy.length - 1);
          for (int bucket = Math.max(first, 0); bucket <= last; bucket++) {
            long bucketStart = bucket * bucketNs;
            long overlap = Math.min(endNs, bucketStart + bucketNs) - Math.max(startNs, bucketStart);
            if (overlap > 0) busy[bucket] += overlap;
          }
        });
      }
      catch (IOException e) {
        Logger.getInstance(HaxeCallChartTab.class).warn("could not read the zone capture", e);
        return List.of();
      }
      List<TracySession.PlotPoint> points = new ArrayList<>(busy.length);
      for (int bucket = 0; bucket < busy.length; bucket++) {
        double percent = Math.min(busy[bucket] * 100.0 / bucketNs, 100.0);
        points.add(new TracySession.PlotPoint(bucket * bucketNs, percent));
      }
      return points;
    }

    /**
     * Engine-emitted GPU counters arrive as tracy PLOTS. Convention: a plot
     * named "gpu*" charts here — containing "mem" as bytes on the GPU
     * memory lane, anything else as 0..100 percent on the GPU load lane.
     * Plots outside the convention stay uncharted.
     */
    private Map<String, List<TracySession.PlotPoint>> gpuPlots(boolean memory) {
      Map<String, List<TracySession.PlotPoint>> series = new TreeMap<>();
      for (Map.Entry<String, List<TracySession.PlotPoint>> plot : store.session().plots().entrySet()) {
        String name = plot.getKey().toLowerCase(Locale.ROOT);
        if (!name.startsWith("gpu")) continue;
        if (name.contains("mem") == memory) series.put(plot.getKey(), plot.getValue());
      }
      return series;
    }

    @Override
    public List<FrameSlice> frameBreakdown(int threadId, UsSpan frame) {
      // top-level zone time inside the span, exact and clipped to it -
      // the engine's own zone names say what each share is (render, update...)
      long fromNs = frame.startUs() * 1000;
      long toNs = frame.endUs() * 1000;
      Map<String, long[]> byFunction = new HashMap<>();
      try {
        store.scanZones(threadId, fromNs, toNs, 0, (thread, depth, startNs, endNs, location) -> {
          if (depth != 0) return;
          long clippedNs = Math.min(endNs, toNs) - Math.max(startNs, fromNs);
          if (clippedNs > 0) {
            byFunction.computeIfAbsent(location.function(), key -> new long[1])[0] += clippedNs;
          }
        });
      }
      catch (IOException e) {
        Logger.getInstance(HaxeCallChartTab.class).warn("could not read the zone capture", e);
        return List.of();
      }
      List<FrameSlice> slices = new ArrayList<>();
      byFunction.forEach((function, ns) -> slices.add(new FrameSlice(function, ns[0] / 1000)));
      slices.sort(Comparator.comparingLong(FrameSlice::totalUs).reversed());
      return slices;
    }

    @Override
    public List<TimelineEvent> events() {
      return store.session().events();
    }
  }

  /** One thread's lane inputs, computable OFF the EDT (several linear scans over the capture). */
  private record ThreadLaneData(GcSpans gc, List<UsSpan> frameSpans, List<TimelineEvent> events,
                                Map<CurveCategory, Map<String, List<TracySession.PlotPoint>>> curves) {
  }

  private static ThreadLaneData computeThreadData(ChartData data, int threadId) {
    Map<CurveCategory, Map<String, List<TracySession.PlotPoint>>> curves = new EnumMap<>(CurveCategory.class);
    for (CurveCategory category : CurveCategory.values()) {
      curves.put(category, data.curveSeries(category, threadId));
    }
    return new ThreadLaneData(data.gcSpansFor(threadId), data.frameSpansFor(threadId), data.events(), curves);
  }

  private static void assignThreadData(LaneState lanes, ThreadLaneData computed, ChartData data, int threadId) {
    lanes.gcSpans = computed.gc().spans();
    lanes.gcSpanKindKey = computed.gc().spanKindKey();
    lanes.gcSpanInfo = computed.gc().spanInfo();
    lanes.frameSpans = computed.frameSpans();
    lanes.events = computed.events();
    lanes.curves.putAll(computed.curves());
    List<UsSpan> gcSpans = computed.gc().spans();
    lanes.frameBreakdown = span -> breakdownRows(data.frameBreakdown(threadId, span), span.durationUs(),
                                                 overlapUs(gcSpans, span));
    lanes.frameImage = data::frameImage;
  }

  /** Pulls one thread's lane inputs out of the data source into the lane state. */
  private static void loadThreadData(ChartData data, LaneState lanes, int threadId) {
    assignThreadData(lanes, computeThreadData(data, threadId), data, threadId);
  }

  /** How often a live tab polls its growing session file; a tick that finds nothing new costs one incremental poll. */
  private static final int LIVE_REFRESH_MS = 250;
  /** The live starting view: a sliding window this wide pinned to the tail. */
  private static final long LIVE_WINDOW_US = 2_000_000;

  /**
   * The live pump. Per-tick cost stays FLAT as the session grows: an
   * incremental reader consumes only the newly appended records, and the
   * flame tree builds only for the visible window (plus one viewport of
   * margin each side) — not the whole session — while the chart follows
   * the tail. Everything heavy runs on a pooled thread; the EDT only swaps
   * the results in. One final tick when the capture completes.
   */
  private static void installLiveRefresh(Path sessionFile, HaxeLiveCaptures.Entry live, @Nullable Disposable parent,
                                         ChartData[] data, LaneState lanes, HaxeCallChartPanel chart,
                                         HaxeChartMinimapPanel minimap, WindowLoader loader,
                                         ComboBox<ProfilerThread> threadPicker, ActionListener threadListener,
                                         HaxeChartViewSettings viewSettings, long[] liveView) {
    AtomicBoolean busy = new AtomicBoolean();
    HxtLiveSession[] reader = new HxtLiveSession[1];
    boolean[] windowStarted = {false};
    Consumer<Boolean> tick = force -> {
      if (!busy.compareAndSet(false, true)) return;
      int threadId = threadPicker.getSelectedItem() instanceof ProfilerThread thread ? thread.id() : -1;
      long viewFromUs = liveView[2] != 0 ? Long.MIN_VALUE / 2 : liveView[0] - liveView[1];
      long viewToUs = liveView[2] != 0 ? Long.MAX_VALUE / 2 : liveView[0] + 2 * liveView[1];
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        SnapshotData fresh = null;
        FlameNode tree = null;
        ThreadLaneData computed = null;
        try {
          if (reader[0] == null) {
            reader[0] = HxtLiveSession.open(sessionFile);
          }
          boolean grew = reader[0].poll();
          if (!force && !grew) {
            busy.set(false);
            return; // nothing new landed since the last tick
          }
          fresh = new SnapshotData(reader[0].snapshot());
          if (threadId >= 0) {
            tree = ProfilerTimeline.flameTree(fresh.snapshot(), threadId, MAX_DEPTH, viewFromUs, viewToUs);
            computed = computeThreadData(fresh, threadId);
          }
        }
        catch (IOException midWrite) {
          fresh = null; // a torn read of a growing file - the next tick retries
        }
        SnapshotData parsed = fresh;
        FlameNode parsedTree = tree;
        ThreadLaneData parsedLanes = computed;
        ApplicationManager.getApplication().invokeLater(() -> {
          busy.set(false);
          if (parsed == null) return;
          data[0] = parsed;
          loader.setData(parsed);
          refreshThreadItems(threadPicker, threadListener, parsed.threads());
          if (!(threadPicker.getSelectedItem() instanceof ProfilerThread thread)
              || parsedTree == null || parsedLanes == null) {
            return;
          }
          assignThreadData(lanes, parsedLanes, parsed, thread.id());
          upgradeLaneDefaults(lanes, viewSettings);
          applyLanes(chart, lanes);
          chart.updateTree(parsedTree);
          minimap.setContent(parsedTree.durationUs(), lanes.frameSpans, List.of());
          if (!windowStarted[0]) {
            windowStarted[0] = chart.startLiveWindow(LIVE_WINDOW_US);
          }
          chart.liveDataAppended();
        });
      });
    };
    Timer refresh = new Timer(LIVE_REFRESH_MS, event -> tick.accept(false));
    refresh.start();
    live.onCompletion(() -> {
      tick.accept(true);
      refresh.stop();
    });
    if (parent != null) {
      Disposer.register(parent, refresh::stop);
    }
  }

  /** Adds newly appeared threads to the picker without firing the selection listener or disturbing the choice. */
  private static void refreshThreadItems(ComboBox<ProfilerThread> picker, ActionListener listener,
                                         List<ProfilerThread> threads) {
    if (picker.getItemCount() == threads.size()) return;
    ProfilerThread selected = (ProfilerThread)picker.getSelectedItem();
    picker.removeActionListener(listener);
    try {
      picker.setModel(new DefaultComboBoxModel<>(threads.toArray(new ProfilerThread[0])));
      if (selected != null) {
        for (ProfilerThread thread : threads) {
          if (thread.id() == selected.id()) {
            picker.setSelectedItem(thread);
            break;
          }
        }
      }
    }
    finally {
      picker.addActionListener(listener);
    }
  }

  /**
   * Live refreshes: a defaults-hidden lane that just GAINED data turns on
   * (the first parse of a streaming capture is thin — GC may only appear
   * after seconds); lanes with an explicit saved choice stay as chosen.
   */
  private static void upgradeLaneDefaults(LaneState lanes, HaxeChartViewSettings settings) {
    Map<String, Boolean> stored = settings.laneVisibility();
    if (!stored.containsKey("frames")) lanes.showFrames |= !lanes.frameSpans.isEmpty();
    if (!stored.containsKey("gc")) lanes.showGc |= !lanes.gcSpans.isEmpty();
    if (!stored.containsKey("events")) lanes.showEvents |= !lanes.events.isEmpty();
    for (CurveCategory category : CurveCategory.values()) {
      if (!stored.containsKey("curve:" + category.name()) && !lanes.curves.get(category).isEmpty()) {
        lanes.showCurve.put(category, true);
      }
    }
  }

  /**
   * The first data load decides the visibility: the user's saved choice
   * where one exists, else the data-driven default (a lane without data
   * starts hidden); dialog choices stick afterwards.
   */
  private static void applyLaneDefaults(LaneState lanes, HaxeChartViewSettings settings) {
    if (lanes.defaultsApplied) return;
    lanes.defaultsApplied = true;
    Map<String, Boolean> stored = settings.laneVisibility();
    lanes.showFrames = stored.getOrDefault("frames", !lanes.frameSpans.isEmpty());
    lanes.showGc = stored.getOrDefault("gc", !lanes.gcSpans.isEmpty());
    lanes.showEvents = stored.getOrDefault("events", !lanes.events.isEmpty());
    lanes.showCalls = stored.getOrDefault("calls", true);
    for (CurveCategory category : CurveCategory.values()) {
      boolean shown = stored.getOrDefault("curve:" + category.name(), !lanes.curves.get(category).isEmpty());
      lanes.showCurve.put(category, shown);
    }
  }

  /** Rebuilds the chart's lanes from the toggle state; an enabled lane without data renders its placeholder. */
  private static void applyLanes(HaxeCallChartPanel chart, LaneState lanes) {
    List<MarkerLane> visible = new ArrayList<>();
    if (lanes.showFrames) {
      List<UsSpan> frameSpans = lanes.frameSpans;
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.frames"),
                                 span -> frameTitle(frameSpans, span),
                                 frameSpans, FRAME_LANE_EVEN, FRAME_LANE_ODD, null, lanes.frameBreakdown));
    }
    if (lanes.showGc) {
      String gcTitle = HaxeProfilerBundle.message(lanes.gcSpanKindKey);
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.gc"),
                                 span -> gcTitle,
                                 lanes.gcSpans, GC_LANE, GC_LANE, lanes.gcSpanInfo, null));
    }
    chart.setLanes(visible);
    chart.setCurveLanes(curveLanes(lanes));
    chart.setEvents(lanes.showEvents ? eventMarks(lanes.events) : List.of(), lanes.showEvents);
    chart.setCallsVisible(lanes.showCalls);
  }

  /**
   * "Frame N", numbered from 1 in capture order. The span covers wait +
   * work with the frame's work at its END: the mark lands when rendering
   * finishes (openfl marks after Stage rendering, the last per-frame step).
   */
  private static String frameTitle(List<UsSpan> spans, UsSpan span) {
    return HaxeProfilerBundle.message("haxe.profiler.callchart.span.frame", String.valueOf(spans.indexOf(span) + 1));
  }

  /** Core events (ns, RGB int) as the chart's marks (µs, optional Color). */
  private static List<HaxeCallChartPanel.TimeEvent> eventMarks(List<TimelineEvent> events) {
    return events.stream()
      .map(event -> new HaxeCallChartPanel.TimeEvent(event.timeNs() / 1000, event.text(),
                                                     event.color() == 0 ? null : new Color(event.color())))
      .toList();
  }

  /**
   * The enabled categories' curve bands in display order, one per series,
   * name-ordered so colors stay stable across thread switches; an enabled
   * category without series contributes one empty placeholder band.
   */
  private static List<HaxeCallChartPanel.CurveLane> curveLanes(LaneState lanes) {
    List<HaxeCallChartPanel.CurveLane> curves = new ArrayList<>();
    for (CurveCategory category : CurveCategory.values()) {
      if (!lanes.showCurve.get(category)) continue;
      Map<String, List<TracySession.PlotPoint>> series = lanes.curves.get(category);
      if (series.isEmpty()) {
        String label = HaxeProfilerBundle.message(category.labelKey);
        curves.add(HaxeCallChartPanel.CurveLane.of(label, category.unit, List.of(), curveColor(category, 0)));
        continue;
      }
      // the provider owns the series order (pools name-sorted, thread activity before system CPU)
      int index = 0;
      for (Map.Entry<String, List<TracySession.PlotPoint>> curve : series.entrySet()) {
        List<HaxeCallChartPanel.CurvePoint> points = curve.getValue().stream()
          .map(point -> new HaxeCallChartPanel.CurvePoint(point.timeNs() / 1000, point.value()))
          .toList();
        curves.add(HaxeCallChartPanel.CurveLane.of(curve.getKey(), category.unit, points, curveColor(category, index++)));
      }
    }
    return curves;
  }

  private static Color curveColor(CurveCategory category, int index) {
    return switch (category) {
      case MEMORY -> MEMORY_LANE_COLORS[index % MEMORY_LANE_COLORS.length];
      case GPU_MEMORY -> GPU_MEMORY_LANE;
      case CPU_LOAD -> CPU_LANE;
      case GPU_LOAD -> GPU_LOAD_LANE;
    };
  }

  /** The summary categories a frame's shares roll up into, in display order. */
  private enum ShareCategory {
    SCRIPT("haxe.profiler.callchart.category.script", new JBColor(0x548AF7, 0x4E76C4)),
    RENDER("haxe.profiler.callchart.category.render", new JBColor(0x59A869, 0x527C5B)),
    GC("haxe.profiler.callchart.category.gc", GC_LANE),
    IDLE("haxe.profiler.callchart.category.idle", new JBColor(0xA8ADBD, 0x6F737A));

    final String labelKey;
    final Color color;

    ShareCategory(String labelKey, Color color) {
      this.labelKey = labelKey;
      this.color = color;
    }

    /** The pseudo-frame vocabulary the collectors emit; real function roots are script work. */
    static ShareCategory of(String sliceName) {
      if (IDLE_ROOTS.contains(sliceName)) return IDLE;
      if ("[render]".equals(sliceName)) return RENDER;
      if (isGcFrameName(sliceName)) return GC;
      return SCRIPT;
    }
  }

  /** Roots meaning the thread was NOT executing application code: coalesced idle ticks, collector overhead marks and the samplers' event-loop pseudo-frames. */
  private static final Set<String> IDLE_ROOTS = Set.of("[idle]", "[io]", "[execute-queued]", "[profiler]");
  private static final Color FOLDED_PARTS = new JBColor(0xC79A50, 0x8A6D3F);

  /** GC work however a collector spells it: the pseudo-frames, or hxcpp's own GC entry points sitting MID-stack under the allocation site. */
  private static boolean isGcFrameName(String symbol) {
    return "[gc]".equals(symbol) || "[mark]".equals(symbol) || "[sweep]".equals(symbol) || symbol.startsWith("GC::");
  }

  /** The microseconds of {@code spans} falling inside {@code frame}. */
  private static long overlapUs(List<UsSpan> spans, UsSpan frame) {
    long overlap = 0;
    for (UsSpan span : spans) {
      overlap += Math.max(0, Math.min(span.endUs(), frame.endUs()) - Math.max(span.startUs(), frame.startUs()));
    }
    return overlap;
  }

  /**
   * A frame breakdown as bar rows: the category summary (script, render,
   * GC, idle — the uncovered remainder counts as idle, the wait for the
   * next frame) above the biggest individual parts, small ones folded
   * into "other". Each part row wears its category's color.
   */
  private static HaxeFrameBreakdownView.Rows breakdownRows(List<FrameSlice> slices, long frameUs, long gcSpanUs) {
    if (slices.isEmpty() || frameUs <= 0) return new HaxeFrameBreakdownView.Rows(List.of(), List.of());

    Map<ShareCategory, Long> byCategory = new EnumMap<>(ShareCategory.class);
    long covered = 0;
    for (FrameSlice slice : slices) {
      byCategory.merge(ShareCategory.of(slice.name()), slice.totalUs(), Long::sum);
      covered += slice.totalUs();
    }
    // a long collection stops the world: no pushes, no GC-named samples -
    // the stall's clock ticks land in the first APP sample after it. The
    // GC lane's spans are the runtime's own figure, so the part of them
    // the samples missed moves from Scripting into GC.
    long hiddenGcUs = Math.min(Math.max(gcSpanUs - byCategory.getOrDefault(ShareCategory.GC, 0L), 0),
                               byCategory.getOrDefault(ShareCategory.SCRIPT, 0L));
    if (hiddenGcUs > 0) {
      byCategory.merge(ShareCategory.GC, hiddenGcUs, Long::sum);
      byCategory.merge(ShareCategory.SCRIPT, -hiddenGcUs, Long::sum);
    }
    if (frameUs > covered) {
      byCategory.merge(ShareCategory.IDLE, frameUs - covered, Long::sum);
    }
    List<HaxeFrameBreakdownView.Row> summary = new ArrayList<>();
    for (ShareCategory category : ShareCategory.values()) {
      Long totalUs = byCategory.get(category);
      if (totalUs != null && totalUs > 0) {
        String label = HaxeProfilerBundle.message(category.labelKey);
        summary.add(new HaxeFrameBreakdownView.Row(label, totalUs, frameUs, category.color, true));
      }
    }

    List<HaxeFrameBreakdownView.Row> parts = new ArrayList<>();
    long folded = 0;
    for (int i = 0; i < slices.size(); i++) {
      FrameSlice slice = slices.get(i);
      if (ShareCategory.of(slice.name()) == ShareCategory.IDLE) continue;
      if (parts.size() < BREAKDOWN_LINES) {
        Color color = ShareCategory.of(slice.name()).color;
        parts.add(new HaxeFrameBreakdownView.Row(slice.name(), slice.totalUs(), frameUs, color, false));
      }
      else {
        folded += slice.totalUs();
      }
    }
    if (folded > 0) {
      String label = HaxeProfilerBundle.message("haxe.profiler.callchart.frame.other");
      parts.add(new HaxeFrameBreakdownView.Row(label, folded, frameUs, FOLDED_PARTS, false));
    }
    return new HaxeFrameBreakdownView.Rows(summary, parts);
  }

  /** Zoom controls beside the chart, plus the Configure View dialog and a shortcut to the profiler settings page. */
  private static JComponent chartToolbar(Project project, HaxeCallChartPanel chart, Runnable configureView) {
    DefaultActionGroup actions = new DefaultActionGroup();
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.zoom.in"),
                                       AllIcons.General.ZoomIn, event -> chart.zoomIn()));
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.zoom.out"),
                                       AllIcons.General.ZoomOut, event -> chart.zoomOut()));
    actions.addSeparator();
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.expand.all"),
                                       AllIcons.Actions.Expandall, event -> chart.expandAll()));
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.collapse.all"),
                                       AllIcons.Actions.Collapseall, event -> chart.collapseAll()));
    actions.addSeparator();
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.configure.view"),
                                       AllIcons.Actions.Show, event -> configureView.run()));
    String settingsPage = HaxeProfilerBundle.message("haxe.profiler.configurable.name");
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.open.settings"),
                                       AllIcons.General.Settings,
                                       event -> ShowSettingsUtil.getInstance().showSettingsDialog(project, settingsPage)));

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HaxeCallChart", actions, false);
    toolbar.setTargetComponent(chart);
    return toolbar.getComponent();
  }

  /**
   * A selected marker-lane span: what it is, when it ran, for how long —
   * and the lane's per-span detail if it has one. A frame's breakdown and
   * screenshot load on a pooled thread (they may read the capture file)
   * and only land while this selection is still the newest content.
   */
  private static void showSpanDetails(HaxeStackDetailPanel details, LaneState lanes, MarkerLane lane, UsSpan span) {
    String timeRange = HaxeProfilerFormats.formatRange(span.startUs(), span.endUs());
    List<String> headerLines = new ArrayList<>(List.of(lane.spanTitle().apply(span), timeRange));
    String spanInfo = lane.spanInfo() == null ? null : lane.spanInfo().apply(span);
    if (spanInfo != null) {
      headerLines.add(spanInfo);
    }
    details.showStack(headerLines, List.of());

    Function<UsSpan, HaxeFrameBreakdownView.Rows> breakdown = lane.breakdown();
    if (breakdown == null) return;
    int expected = details.revision();
    Function<UsSpan, Image> imageLoader = lanes.frameImage;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      HaxeFrameBreakdownView.Rows rows = breakdown.apply(span);
      Image image = imageLoader == null ? null : imageLoader.apply(span);
      ApplicationManager.getApplication().invokeLater(() -> {
        if (details.revision() != expected) return; // a newer selection took the panel
        // an empty breakdown says so - silence reads as broken (a sampled
        // frame may simply have caught no ticks)
        if (rows.isEmpty()) {
          List<String> all = new ArrayList<>(headerLines);
          all.add(HaxeProfilerBundle.message("haxe.profiler.callchart.no.data"));
          details.showStack(all, List.of());
        }
        else {
          details.showBreakdown(headerLines, rows);
        }
        details.showImage(image);
      });
    });
  }

  /** A selected events-row mark: its text and the instant it fired. */
  private static void showEventDetails(HaxeStackDetailPanel details, HaxeCallChartPanel.TimeEvent event) {
    details.showStack(List.of(event.text(), HaxeProfilerFormats.formatInstant(event.timeUs())), List.of());
  }

  /**
   * A selected memory reading: the pool, the bytes live at the clicked
   * instant and, when the value has been holding for a while, when it last
   * changed — the curve is a step function, so between changes it is flat.
   */
  private static void showCurveDetails(HaxeStackDetailPanel details, HaxeCallChartPanel.CurveSelection selection) {
    String value = selection.lane().formatted(selection.lastChange().value());
    String instant = HaxeProfilerFormats.formatInstant(selection.instantUs());
    List<String> headerLines = new ArrayList<>(List.of(selection.lane().name(), value, instant));
    if (selection.instantUs() - selection.lastChange().timeUs() > 1000) {
      String changedAt = HaxeProfilerFormats.formatInstant(selection.lastChange().timeUs());
      headerLines.add(HaxeProfilerBundle.message("haxe.profiler.callchart.memory.since", changedAt));
    }
    details.showStack(headerLines, List.of());
  }

  /** The selected run's summary above its call chain; an empty path restores the hint. */
  private static void showRunDetails(HaxeStackDetailPanel details, List<FlameNode> path) {
    if (path.isEmpty()) {
      details.showText(HaxeProfilerBundle.message("haxe.profiler.callchart.details.hint"));
      return;
    }
    FlameNode run = path.getLast();
    String symbol = run.frame() == null ? "" : run.frame().symbol();
    String timeRange = HaxeProfilerFormats.formatRange(run.startUs(), run.endUs());
    String samples = HaxeProfilerBundle.message("haxe.profiler.callchart.samples", run.samples());
    List<StackFrame> stack = path.stream().map(FlameNode::frame).filter(Objects::nonNull).toList();
    details.showStack(List.of(symbol, timeRange, samples), stack);
  }

  /**
   * Reloads the chart's tree from the store as the view moves: the visible
   * window plus one viewport of margin each side, with zones finer than
   * 1/{@value #RESOLUTION} of the view dropped — so every window loads at
   * full usable detail no matter how long the capture is. Debounced;
   * results land on the EDT and stale generations are discarded.
   */
  private static final class WindowLoader {
    static final int RESOLUTION = 32_768;
    private static final int DEBOUNCE_MS = 150;

    private ChartData data;
    private final HaxeCallChartPanel chart;
    private final Timer debounce = new Timer(DEBOUNCE_MS, event -> load());
    private final AtomicInteger generation = new AtomicInteger();
    private int threadId;
    private long loadedFromUs;
    private long loadedToUs;
    private long loadedMinDurationUs;
    private long pendingFromUs;
    private long pendingToUs;
    private long pendingMinDurationUs;

    WindowLoader(ChartData data, HaxeCallChartPanel chart) {
      this.data = data;
      this.chart = chart;
      debounce.setRepeats(false);
    }

    /** A live refresh swapped the data source; window loads read the new one from here on. */
    void setData(ChartData data) {
      this.data = data;
    }

    /** The thread's whole-session tree is about to load at {@code floorUs} resolution. */
    void initialize(int threadId, long floorUs) {
      this.threadId = threadId;
      loadedFromUs = 0;
      loadedToUs = Long.MAX_VALUE;
      loadedMinDurationUs = floorUs;
      generation.incrementAndGet();
      debounce.stop();
    }

    void viewChanged(long startUs, long visibleUs, boolean wholeSession) {
      if (!data.windowedLoads()) return;
      long needFromUs = wholeSession ? 0 : Math.max(0, startUs - visibleUs);
      long needToUs = wholeSession ? Long.MAX_VALUE : startUs + 2 * visibleUs;
      long needMinDurationUs = visibleUs / RESOLUTION;
      boolean covered = needFromUs >= loadedFromUs && needToUs <= loadedToUs
                        && needMinDurationUs >= loadedMinDurationUs;
      if (covered) return;
      pendingFromUs = needFromUs;
      pendingToUs = needToUs;
      pendingMinDurationUs = needMinDurationUs;
      debounce.restart();
    }

    private void load() {
      int expected = generation.incrementAndGet();
      int thread = threadId;
      long fromUs = pendingFromUs;
      long toUs = pendingToUs;
      long minDurationUs = pendingMinDurationUs;
      ApplicationManager.getApplication().executeOnPooledThread(() -> {
        FlameNode tree = data.treeFor(thread, fromUs, toUs, minDurationUs);
        ApplicationManager.getApplication().invokeLater(() -> {
          if (generation.get() != expected) return; // a newer view superseded this load
          loadedFromUs = fromUs;
          loadedToUs = toUs;
          loadedMinDurationUs = minDurationUs;
          chart.updateTree(tree);
        });
      });
    }
  }

  /**
   * The current thread's lane data plus what the user chose to see. Every
   * toggle stays operable; the first data load decides the DEFAULTS (a lane
   * without data starts hidden) and user choices survive thread switches.
   */
  private static final class LaneState {
    boolean defaultsApplied;
    boolean showFrames;
    boolean showGc;
    boolean showEvents;
    boolean showCalls = true;
    final Map<CurveCategory, Boolean> showCurve = new EnumMap<>(CurveCategory.class);
    final Map<CurveCategory, Map<String, List<TracySession.PlotPoint>>> curves = new EnumMap<>(CurveCategory.class);
    List<UsSpan> frameSpans = List.of();
    List<UsSpan> gcSpans = List.of();
    String gcSpanKindKey = "haxe.profiler.callchart.span.gc";
    @Nullable Function<UsSpan, String> gcSpanInfo;
    List<TimelineEvent> events = List.of();
    /** Loads a selected frame's breakdown lines; runs on a pooled thread. */
    @Nullable Function<UsSpan, HaxeFrameBreakdownView.Rows> frameBreakdown;
    /** Loads a selected frame's screenshot; runs on a pooled thread. */
    @Nullable Function<UsSpan, Image> frameImage;

    LaneState() {
      for (CurveCategory category : CurveCategory.values()) {
        showCurve.put(category, false);
        curves.put(category, Map.of());
      }
    }
  }

  /**
   * The details pane's collapse: the chevron above it folds the pane into a
   * thin strip at the right edge (freeing the width for the chart), the
   * strip's chevron brings it back; the choice persists with the view
   * settings, the expanded width survives one collapse/expand round trip.
   */
  private static final class DetailsCollapse {
    private static final float DEFAULT_PROPORTION = 0.72f;

    private final OnePixelSplitter splitter;
    private final HaxeChartViewSettings settings;
    private final JComponent expandedPane;
    private final JComponent collapsedStrip;
    private float expandedProportion = DEFAULT_PROPORTION;

    DetailsCollapse(OnePixelSplitter splitter, HaxeStackDetailPanel details, HaxeChartViewSettings settings) {
      this.splitter = splitter;
      this.settings = settings;
      expandedPane = paneWithCollapseButton(details);
      collapsedStrip = strip();
      if (settings.isDetailsCollapsed()) {
        splitter.setSecondComponent(collapsedStrip);
        splitter.setProportion(1f);
      }
      else {
        splitter.setSecondComponent(expandedPane);
      }
    }

    private JComponent paneWithCollapseButton(HaxeStackDetailPanel details) {
      IconButton icon = new IconButton(HaxeProfilerBundle.message("haxe.profiler.callchart.details.collapse"),
                                       AllIcons.General.ArrowRight);
      BorderLayoutPanel bar = new BorderLayoutPanel();
      bar.addToRight(new InplaceButton(icon, event -> collapse()));
      BorderLayoutPanel pane = new BorderLayoutPanel();
      pane.addToTop(bar);
      pane.addToCenter(details);
      return pane;
    }

    private JComponent strip() {
      IconButton icon = new IconButton(HaxeProfilerBundle.message("haxe.profiler.callchart.details.expand"),
                                       AllIcons.General.ArrowLeft);
      BorderLayoutPanel strip = new BorderLayoutPanel() {
        @Override
        public Dimension getMinimumSize() {
          return new Dimension(JBUI.scale(24), 0);
        }
      };
      strip.addToTop(new InplaceButton(icon, event -> expand()));
      return strip;
    }

    private void collapse() {
      expandedProportion = splitter.getProportion();
      splitter.setSecondComponent(collapsedStrip);
      splitter.setProportion(1f);
      settings.setDetailsCollapsed(true);
    }

    private void expand() {
      splitter.setSecondComponent(expandedPane);
      splitter.setProportion(expandedProportion);
      settings.setDetailsCollapsed(false);
    }
  }
}
