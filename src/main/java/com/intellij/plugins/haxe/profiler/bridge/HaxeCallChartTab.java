package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.HaxeCallChartPanel.MarkerLane;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.model.ProfilerThread;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.FlameNode;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.UsSpan;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import com.intellij.plugins.haxe.profiler.tracy.TracyZoneTrees;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.dsl.listCellRenderer.BuilderKt;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.ScrollPaneConstants;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

  /** The per-thread chart inputs one capture kind provides. */
  private interface ChartData {
    List<ProfilerThread> threads();

    FlameNode treeFor(int threadId);

    GcSpans gcSpansFor(int threadId);

    List<UsSpan> frameSpansFor(int threadId);
  }

  private record GcSpans(@NotNull List<UsSpan> spans, boolean frameAccurate) {
  }

  private HaxeCallChartTab() {
  }

  @NotNull
  static JComponent create(@NotNull Project project, @NotNull ProfilerSnapshot snapshot) {
    return create(project, new SnapshotData(snapshot));
  }

  @NotNull
  static JComponent create(@NotNull Project project, @NotNull TracySession session) {
    return create(project, new ZoneData(session));
  }

  @NotNull
  private static JComponent create(@NotNull Project project, @NotNull ChartData data) {
    HaxeStackDetailPanel details = new HaxeStackDetailPanel(project);
    HaxeCallChartPanel chart = new HaxeCallChartPanel(frame -> HaxeCallStackElement.navigateToFrame(project, frame),
                                                      path -> showRunDetails(details, path),
                                                      (lane, span) -> showSpanDetails(details, lane, span));
    LaneState lanes = new LaneState();

    ComboBox<ProfilerThread> threadPicker = new ComboBox<>(data.threads().toArray(new ProfilerThread[0]));
    threadPicker.setRenderer(BuilderKt.textListCellRenderer("", ProfilerThread::name));
    Runnable applyThread = () -> {
      ProfilerThread thread = (ProfilerThread)threadPicker.getSelectedItem();
      if (thread != null) {
        chart.setTree(data.treeFor(thread.id()));
        GcSpans gc = data.gcSpansFor(thread.id());
        lanes.gcSpans = gc.spans();
        lanes.gcFrameAccurate = gc.frameAccurate();
        lanes.frameSpans = data.frameSpansFor(thread.id());
        applyLanes(chart, lanes);
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
    public FlameNode treeFor(int threadId) {
      return ProfilerTimeline.flameTree(snapshot, threadId, MAX_DEPTH);
    }

    @Override
    public GcSpans gcSpansFor(int threadId) {
      // sample-flagged spans (HL) first; telemetry captures carry GC as
      // per-frame time events instead - stop-the-world, so not per-thread
      List<UsSpan> flagged = ProfilerTimeline.gcSpans(snapshot, threadId);
      if (!flagged.isEmpty()) return new GcSpans(flagged, false);
      return new GcSpans(ProfilerTimeline.gcSpansFromEvents(snapshot), true);
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
  }

  /** Tracy sessions: exact zone trees; no GC source (upstream emits no GC zones yet); frames from marks. */
  private record ZoneData(@NotNull TracySession session) implements ChartData {
    @Override
    public List<ProfilerThread> threads() {
      return TracyZoneTrees.threads(session);
    }

    @Override
    public FlameNode treeFor(int threadId) {
      return TracyZoneTrees.threadTree(session, threadId);
    }

    @Override
    public GcSpans gcSpansFor(int threadId) {
      return new GcSpans(List.of(), false);
    }

    @Override
    public List<UsSpan> frameSpansFor(int threadId) {
      return TracyZoneTrees.frameSpans(session);
    }
  }

  /** Rebuilds the chart's marker lanes from the toggle state; a lane with no data stays off regardless. */
  private static void applyLanes(HaxeCallChartPanel chart, LaneState lanes) {
    List<MarkerLane> visible = new ArrayList<>();
    if (lanes.showFrames && !lanes.frameSpans.isEmpty()) {
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.frames"),
                                 HaxeProfilerBundle.message("haxe.profiler.callchart.span.frame"),
                                 lanes.frameSpans, FRAME_LANE_EVEN, FRAME_LANE_ODD));
    }
    if (lanes.showGc && !lanes.gcSpans.isEmpty()) {
      String spanKind = HaxeProfilerBundle.message(lanes.gcFrameAccurate
                                                   ? "haxe.profiler.callchart.span.gc.frame"
                                                   : "haxe.profiler.callchart.span.gc");
      visible.add(new MarkerLane(HaxeProfilerBundle.message("haxe.profiler.callchart.lane.gc"),
                                 spanKind, lanes.gcSpans, GC_LANE, GC_LANE));
    }
    chart.setLanes(visible);
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
    actions.add(framesToggle);
    actions.add(gcToggle);

    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("HaxeCallChart", actions, false);
    toolbar.setTargetComponent(chart);
    return toolbar.getComponent();
  }

  /** A selected marker-lane span: what it is, when it ran and for how long. */
  private static void showSpanDetails(HaxeStackDetailPanel details, MarkerLane lane, UsSpan span) {
    String timeRange = HaxeCallChartPanel.formatRange(span.startUs(), span.endUs());
    details.showStack(List.of(lane.spanKind(), timeRange), List.of());
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

  /** The current thread's lane data plus what the user chose to see. */
  private static final class LaneState {
    boolean showFrames = true;
    boolean showGc = true;
    List<UsSpan> frameSpans = List.of();
    List<UsSpan> gcSpans = List.of();
    /** True when the GC spans came from per-frame time events - exact widths, frame-accurate positions. */
    boolean gcFrameAccurate;
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
