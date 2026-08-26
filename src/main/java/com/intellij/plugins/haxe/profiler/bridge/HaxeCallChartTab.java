package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.HaxeCallChartPanel.MarkerLane;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneStore;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.model.TimelineEvent;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.FlameNode;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.UsSpan;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracyZone;
import com.intellij.plugins.haxe.profiler.tracy.TracyZoneTrees;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Call Chart tab: one thread's capture explorable over a time axis, the
 * concept Chrome DevTools and the Android profiler render
 * ({@link HaxeCallChartPanel} over a per-thread flame tree). Two data
 * sources feed the same UI: sampled snapshots (reconstructed runs) and
 * tracy zone sessions (exact measured intervals). A thread picker on top
 * switches the charted thread; clicking a run fills the detail panel on
 * the right; the toolbar on the left zooms and toggles the marker lanes.
 */
final class HaxeCallChartTab {

  /** Deeper frames fold into their ancestor; the flamegraph tab still shows full depth. */
  private static final int MAX_DEPTH = 64;

  private static final Color GC_LANE = new JBColor(0xD66A6A, 0xA14A4A);
  private static final Color FRAME_LANE_EVEN = new JBColor(0x9CB8D6, 0x53687D);
  private static final Color FRAME_LANE_ODD = new JBColor(0xC4D4E4, 0x3E4E5E);
  private static final Color[] MEMORY_LANE_COLORS = {
    new JBColor(0x7FB58A, 0x4E7157),
    new JBColor(0x8AA6C8, 0x50647C)};

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

    /** Live-bytes-over-time per allocation pool; process-wide, so not per thread. */
    Map<String, List<TracySession.PlotPoint>> memoryCurves();
  }

  /** A GC lane's spans plus how the detail view describes ONE of them. */
  private record GcSpans(@NotNull List<UsSpan> spans, @NotNull String spanKindKey,
                         @Nullable Function<UsSpan, String> spanInfo) {
  }

  private HaxeCallChartTab() {
  }

  @NotNull
  static JComponent create(@NotNull Project project, @NotNull ProfilerSnapshot snapshot) {
    return create(project, new SnapshotData(snapshot));
  }

  @NotNull
  static JComponent create(@NotNull Project project, @NotNull HxtZoneStore store) {
    return create(project, new ZoneData(store));
  }

  @NotNull
  private static JComponent create(@NotNull Project project, @NotNull ChartData data) {
    HaxeStackDetailPanel details = new HaxeStackDetailPanel(project);
    HaxeCallChartPanel chart = new HaxeCallChartPanel(frame -> HaxeCallStackElement.navigateToFrame(project, frame),
                                                      path -> showRunDetails(details, path),
                                                      (lane, span) -> showSpanDetails(details, lane, span),
                                                      selection -> showCurveDetails(details, selection),
                                                      event -> showEventDetails(details, event));
    LaneState lanes = new LaneState();

    HaxeChartMinimapPanel minimap = new HaxeChartMinimapPanel(chart::setView);
    WindowLoader loader = new WindowLoader(data, chart);
    chart.setViewListener((startUs, visibleUs, wholeSession) -> {
      minimap.showWindow(startUs, visibleUs, wholeSession);
      loader.viewChanged(startUs, visibleUs, wholeSession);
    });

    ComboBox<ProfilerThread> threadPicker = new ComboBox<>(data.threads().toArray(new ProfilerThread[0]));
    threadPicker.setRenderer(BuilderKt.textListCellRenderer("", ProfilerThread::name));
    Runnable applyThread = () -> {
      ProfilerThread thread = (ProfilerThread)threadPicker.getSelectedItem();
      if (thread != null) {
        long floorUs = data.windowedLoads() ? data.durationUs() / WindowLoader.RESOLUTION : 0;
        loader.initialize(thread.id(), floorUs);
        FlameNode tree = data.treeFor(thread.id(), 0, Long.MAX_VALUE, floorUs);
        chart.setTree(tree);
        GcSpans gc = data.gcSpansFor(thread.id());
        lanes.gcSpans = gc.spans();
        lanes.gcSpanKindKey = gc.spanKindKey();
        lanes.gcSpanInfo = gc.spanInfo();
        lanes.frameSpans = data.frameSpansFor(thread.id());
        lanes.memoryCurves = data.memoryCurves();
        lanes.events = data.events();
        applyLanes(chart, lanes);
        minimap.setContent(tree.durationUs(), lanes.frameSpans, data.coarseActivity(thread.id()));
      }
    };
    threadPicker.addActionListener(event -> applyThread.run());
    applyThread.run();

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
    chartSide.addToLeft(chartToolbar(chart, lanes));
    chartSide.addToCenter(chartArea);

    OnePixelSplitter splitter = new OnePixelSplitter(false, 0.72f);
    splitter.setFirstComponent(chartSide);
    splitter.setSecondComponent(details);
    return splitter;
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
    public Map<String, List<TracySession.PlotPoint>> memoryCurves() {
      // TODO: derive a heap curve from the v1 telemetry frames' GC stats
      return Map.of();
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
        String freed = HaxeCallChartPanel.formatBytes(sweep.freedBytes());
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
    public Map<String, List<TracySession.PlotPoint>> memoryCurves() {
      return store.session().memoryCurves();
    }

    @Override
    public List<TimelineEvent> events() {
      return store.session().events();
    }
  }

  /** Rebuilds the chart's marker lanes from the toggle state; a lane with no data stays off regardless. */
  private static void applyLanes(HaxeCallChartPanel chart, LaneState lanes) {
    List<MarkerLane> visible = new ArrayList<>();
    if (lanes.showFrames && !lanes.frameSpans.isEmpty()) {
      List<UsSpan> frameSpans = lanes.frameSpans;
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.frames"),
                                 span -> frameTitle(frameSpans, span),
                                 frameSpans, FRAME_LANE_EVEN, FRAME_LANE_ODD, null));
    }
    if (lanes.showGc && !lanes.gcSpans.isEmpty()) {
      String gcTitle = HaxeProfilerBundle.message(lanes.gcSpanKindKey);
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.gc"),
                                 span -> gcTitle,
                                 lanes.gcSpans, GC_LANE, GC_LANE, lanes.gcSpanInfo));
    }
    chart.setLanes(visible);
    chart.setCurveLanes(lanes.showMemory ? memoryLanes(lanes) : List.of());
    chart.setEvents(lanes.showEvents ? eventMarks(lanes.events) : List.of());
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

  /** One curve band per allocation pool, name-ordered so colors stay stable across thread switches. */
  private static List<HaxeCallChartPanel.CurveLane> memoryLanes(LaneState lanes) {
    List<HaxeCallChartPanel.CurveLane> curves = new ArrayList<>();
    for (Map.Entry<String, List<TracySession.PlotPoint>> curve : new TreeMap<>(lanes.memoryCurves).entrySet()) {
      List<HaxeCallChartPanel.CurvePoint> points = curve.getValue().stream()
        .map(point -> new HaxeCallChartPanel.CurvePoint(point.timeNs() / 1000, point.value()))
        .toList();
      Color color = MEMORY_LANE_COLORS[curves.size() % MEMORY_LANE_COLORS.length];
      curves.add(HaxeCallChartPanel.CurveLane.of(curve.getKey(), points, color));
    }
    return curves;
  }

  /** Zoom controls and lane toggles beside the chart; later toggles join the group. */
  private static JComponent chartToolbar(HaxeCallChartPanel chart, LaneState lanes) {
    DefaultActionGroup actions = new DefaultActionGroup();
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.zoom.in"),
                                       AllIcons.General.ZoomIn, event -> chart.zoomIn()));
    actions.add(DumbAwareAction.create(HaxeProfilerBundle.message("haxe.profiler.callchart.zoom.out"),
                                       AllIcons.General.ZoomOut, event -> chart.zoomOut()));
    actions.addSeparator();

    LaneToggle framesToggle = new LaneToggle(() -> HaxeProfilerBundle.message("haxe.profiler.callchart.toggle.frames"),
                                             AllIcons.Debugger.Frame,
                                             () -> lanes.showFrames,
                                             () -> !lanes.frameSpans.isEmpty(),
                                             state -> {
                                               lanes.showFrames = state;
                                               applyLanes(chart, lanes);
                                             });
    LaneToggle gcToggle = new LaneToggle(() -> HaxeProfilerBundle.message("haxe.profiler.callchart.toggle.gc"),
                                         AllIcons.Actions.GC,
                                         () -> lanes.showGc,
                                         () -> !lanes.gcSpans.isEmpty(),
                                         state -> {
                                           lanes.showGc = state;
                                           applyLanes(chart, lanes);
                                         });
    LaneToggle memoryToggle = new LaneToggle(() -> HaxeProfilerBundle.message("haxe.profiler.callchart.toggle.memory"),
                                             AllIcons.Actions.ProfileMemory,
                                             () -> lanes.showMemory,
                                             () -> !lanes.memoryCurves.isEmpty(),
                                             state -> {
                                               lanes.showMemory = state;
                                               applyLanes(chart, lanes);
                                             });
    LaneToggle eventsToggle = new LaneToggle(() -> HaxeProfilerBundle.message("haxe.profiler.callchart.toggle.events"),
                                             AllIcons.Toolwindows.ToolWindowMessages,
                                             () -> lanes.showEvents,
                                             () -> !lanes.events.isEmpty(),
                                             state -> {
                                               lanes.showEvents = state;
                                               applyLanes(chart, lanes);
                                             });
    actions.add(framesToggle);
    actions.add(gcToggle);
    actions.add(memoryToggle);
    actions.add(eventsToggle);

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HaxeCallChart", actions, false);
    toolbar.setTargetComponent(chart);
    return toolbar.getComponent();
  }

  /** A selected marker-lane span: what it is, when it ran, for how long — and the lane's per-span detail if it has one. */
  private static void showSpanDetails(HaxeStackDetailPanel details, MarkerLane lane, UsSpan span) {
    String timeRange = HaxeCallChartPanel.formatRange(span.startUs(), span.endUs());
    List<String> headerLines = new ArrayList<>(List.of(lane.spanTitle().apply(span), timeRange));
    String spanInfo = lane.spanInfo() == null ? null : lane.spanInfo().apply(span);
    if (spanInfo != null) {
      headerLines.add(spanInfo);
    }
    details.showStack(headerLines, List.of());
  }

  /** A selected events-row mark: its text and the instant it fired. */
  private static void showEventDetails(HaxeStackDetailPanel details, HaxeCallChartPanel.TimeEvent event) {
    details.showStack(List.of(event.text(), HaxeCallChartPanel.formatInstant(event.timeUs())), List.of());
  }

  /**
   * A selected memory reading: the pool, the bytes live at the clicked
   * instant and, when the value has been holding for a while, when it last
   * changed — the curve is a step function, so between changes it is flat.
   */
  private static void showCurveDetails(HaxeStackDetailPanel details, HaxeCallChartPanel.CurveSelection selection) {
    String bytes = HaxeCallChartPanel.formatBytes(selection.lastChange().value());
    String instant = HaxeCallChartPanel.formatInstant(selection.instantUs());
    List<String> headerLines = new ArrayList<>(List.of(selection.lane().name(), bytes, instant));
    if (selection.instantUs() - selection.lastChange().timeUs() > 1000) {
      String changedAt = HaxeCallChartPanel.formatInstant(selection.lastChange().timeUs());
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
    String timeRange = HaxeCallChartPanel.formatRange(run.startUs(), run.endUs());
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

    private final ChartData data;
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

  /** The current thread's lane data plus what the user chose to see. */
  private static final class LaneState {
    boolean showFrames = true;
    boolean showGc = true;
    boolean showMemory = true;
    boolean showEvents = true;
    List<UsSpan> frameSpans = List.of();
    List<UsSpan> gcSpans = List.of();
    String gcSpanKindKey = "haxe.profiler.callchart.span.gc";
    @Nullable Function<UsSpan, String> gcSpanInfo;
    Map<String, List<TracySession.PlotPoint>> memoryCurves = Map.of();
    List<TimelineEvent> events = List.of();
  }

  /** One lane's visibility switch; disabled while the capture holds no data for it. */
  private static final class LaneToggle extends ToggleAction implements DumbAware {
    private final BooleanSupplier selected;
    private final BooleanSupplier hasData;
    private final Consumer<Boolean> onChange;

    private LaneToggle(@NotNull Supplier<String> text, @NotNull Icon icon,
                       @NotNull BooleanSupplier selected, @NotNull BooleanSupplier hasData,
                       @NotNull Consumer<Boolean> onChange) {
      super(text, icon);
      this.selected = selected;
      this.hasData = hasData;
      this.onChange = onChange;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return selected.getAsBoolean();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      onChange.accept(state);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabled(hasData.getAsBoolean());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }
}
