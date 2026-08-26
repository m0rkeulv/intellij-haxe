package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.FlameNode;
import com.intellij.plugins.haxe.profiler.timeline.ProfilerTimeline.UsSpan;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Adjustable;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Chrome/Android-style call chart over one thread's time-ordered flame
 * tree ({@link ProfilerTimeline#flameTree}): x = time under a ruler, one row
 * per stack depth with the outermost call on top. Marker lanes between the
 * ruler and the runs carry named span sources on the same axis (frames, GC
 * collections — any provider). Runs too narrow for a pixel paint as
 * 1&nbsp;px slivers so activity stays visible at any zoom; idle spans stay
 * unpainted. The horizontal axis is VIRTUAL: the panel always fills the
 * viewport and paints the window given by a view-start time and scale, so
 * the deepest zoom does not depend on session length (a session-wide
 * component would overflow int pixel coordinates); its own scrollbar
 * ({@link #createHorizontalScrollBar()}) scrolls that axis. Ctrl+wheel
 * zooms around the pointer, shift+wheel pans, plain wheel keeps scrolling
 * the pane vertically; a click selects a run and reports its call chain to
 * the selection listener, double-click opens its Haxe source.
 */
final class HaxeCallChartPanel extends JComponent implements Scrollable {

  /**
   * A named row of spans above the chart; even/odd colors alternate so span
   * boundaries stay visible. {@code spanTitle} names ONE span for the detail
   * view ("Frame 428", "GC sweep"); {@code spanInfo} may add one per-span
   * detail line ("Freed 1.2 MB in 300 objects").
   */
  record MarkerLane(@NotNull String name, @NotNull Function<UsSpan, String> spanTitle, @NotNull List<UsSpan> spans,
                    @NotNull Color evenColor, @NotNull Color oddColor,
                    @Nullable Function<UsSpan, String> spanInfo) {
  }

  record CurvePoint(long timeUs, double value) {
  }

  /** Told after every view mutation (zoom, pan, scroll, resize, new tree) — the minimap and window loader feed off it. */
  interface ViewListener {
    void viewChanged(long viewStartUs, long visibleUs, boolean wholeSession);
  }

  /**
   * A selected reading on a curve band: the clicked instant, and the sample
   * whose value holds there (the last change at or before it).
   */
  record CurveSelection(@NotNull CurveLane lane, long instantUs, @NotNull CurvePoint lastChange) {
  }

  /** One instant mark on the events row — a user-emitted message with an optional color. */
  record TimeEvent(long timeUs, @NotNull String text, @Nullable Color color) {
  }

  /**
   * A named value-over-time band above the chart (memory pools, plots),
   * drawn as a step curve scaled to its own peak: each sample's value holds
   * until the next one.
   */
  record CurveLane(@NotNull String name, @NotNull List<CurvePoint> points, @NotNull Color color, double peak) {
    static CurveLane of(@NotNull String name, @NotNull List<CurvePoint> points, @NotNull Color color) {
      double peak = points.stream().mapToDouble(CurvePoint::value).max().orElse(0);
      return new CurveLane(name, points, color, peak);
    }
  }

  /** Stable per-symbol pastels (light theme) with muted dark-theme partners. */
  private static final Color[] BOX_COLORS = {
    new JBColor(0xF2C09B, 0x8A6A46),
    new JBColor(0xAFD5AF, 0x5E7F5E),
    new JBColor(0xA8C8E8, 0x51687F),
    new JBColor(0xD8BAD8, 0x7A5E76),
    new JBColor(0xE0D898, 0x7F7A50),
    new JBColor(0xA8D8D8, 0x567878),
    new JBColor(0xE0B0A8, 0x7F5F58),
    new JBColor(0xC8C8C8, 0x666666)};
  private static final Color BOX_TEXT = new JBColor(0x1F1F1F, 0xE8E8E8);
  /** Translucent black reads as a border over every palette entry in both themes. */
  private static final Color BOX_BORDER = new Color(0, 0, 0, 90);
  private static final Color RULER_TEXT = new JBColor(0x808080, 0x999999);
  private static final Color RULER_TICK = new JBColor(0xC8C8C8, 0x515151);
  private static final Color SELECTION = new JBColor(0x3574F0, 0x66A3E0);
  private static final Color SELECTION_INNER = new JBColor(0xFFFFFF, 0x1E1E1E);
  /** Fallback mark color for events that sent none. */
  private static final Color EVENT_MARK = new JBColor(0x3574F0, 0x66A3E0);
  private static final long[] STEP_FACTORS = {1, 2, 5};
  private static final double ZOOM_STEP = 1.3;
  /** One zoom-button press doubles or halves the scale. */
  private static final double BUTTON_ZOOM_STEP = 2.0;
  /** Zoom-in floor: a 1 us tracy zone (the model's finest grain) spans 500 px — room for its label. */
  private static final double MIN_US_PER_PIXEL = 0.002;
  /** The horizontal scrollbar's fixed position count; values map linearly onto the session. */
  private static final int SCROLL_RESOLUTION = 1_000_000_000;

  private final Consumer<StackFrame> navigator;
  /** Told the selected run's call chain, outermost first; an empty list on deselection. */
  private final Consumer<List<FlameNode>> selectionListener;
  /** Told the selected marker-lane span; deselection goes through the run listener's empty list. */
  private final BiConsumer<MarkerLane, UsSpan> spanSelectionListener;
  /** Told the selected curve reading (a memory value at the clicked instant). */
  private final Consumer<CurveSelection> curveSelectionListener;
  /** Told the selected events-row mark. */
  private final Consumer<TimeEvent> eventSelectionListener;
  private FlameNode root = new FlameNode(null, 0, 0, 0, List.of(), false);
  private List<MarkerLane> lanes = List.of();
  private List<CurveLane> curveLanes = List.of();
  /** Time-ordered instant marks; empty hides the events row. */
  private List<TimeEvent> events = List.of();
  private @Nullable TimeEvent selectedEvent;
  /** User-dragged curve band heights, kept by lane name so they survive thread switches. */
  private final Map<String, Integer> curveHeights = new HashMap<>();
  private @Nullable FlameNode selected;
  private @Nullable UsSpan selectedSpan;
  private @Nullable MarkerLane selectedSpanLane;
  private @Nullable CurveSelection selectedCurve;
  /** Index of the curve band being resized by a divider drag; -1 = none. */
  private int resizingCurveLane = -1;
  private int resizeStartY;
  private int resizeStartHeight;
  private int rowCount;
  private @Nullable ViewListener viewListener;
  /** Microseconds one pixel covers; 0 = fit the whole capture to the viewport. */
  private double usPerPixel;
  /** The time at the panel's left edge while zoomed; fit mode pins it to the tree start. */
  private long viewStartUs;
  private @Nullable JScrollBar horizontalScrollBar;
  /** Guards against the scrollbar's own change events while this panel updates its model. */
  private boolean syncingScrollBar;

  HaxeCallChartPanel(@NotNull Consumer<StackFrame> navigator,
                     @NotNull Consumer<List<FlameNode>> selectionListener,
                     @NotNull BiConsumer<MarkerLane, UsSpan> spanSelectionListener,
                     @NotNull Consumer<CurveSelection> curveSelectionListener,
                     @NotNull Consumer<TimeEvent> eventSelectionListener) {
    this.navigator = navigator;
    this.selectionListener = selectionListener;
    this.spanSelectionListener = spanSelectionListener;
    this.curveSelectionListener = curveSelectionListener;
    this.eventSelectionListener = eventSelectionListener;
    setOpaque(false);
    ToolTipManager.sharedInstance().registerComponent(this);
    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() == 1) {
          selectAt(event.getPoint());
        }
        else if (event.getClickCount() == 2) {
          navigateAt(event.getPoint());
        }
      }

      @Override
      public void mousePressed(MouseEvent event) {
        int divider = curveDividerAt(event.getY());
        if (divider >= 0) {
          resizingCurveLane = divider;
          resizeStartY = event.getY();
          resizeStartHeight = curveLaneHeight(curveLanes.get(divider));
        }
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        resizingCurveLane = -1;
      }
    });
    addMouseMotionListener(new MouseAdapter() {
      @Override
      public void mouseDragged(MouseEvent event) {
        if (resizingCurveLane >= 0 && resizingCurveLane < curveLanes.size()) {
          resizeCurveLane(curveLanes.get(resizingCurveLane), resizeStartHeight + event.getY() - resizeStartY);
        }
      }

      @Override
      public void mouseMoved(MouseEvent event) {
        boolean onDivider = curveDividerAt(event.getY()) >= 0;
        setCursor(onDivider ? Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR) : Cursor.getDefaultCursor());
      }
    });
    addMouseWheelListener(this::onWheel);
    addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        if (usPerPixel > 0) {
          setViewStart(viewStartUs);
        }
        syncScrollBar();
        fireViewChanged();
      }
    });
  }

  /**
   * The scroller for the virtual horizontal axis, placed by the tab below
   * the chart's scroll pane (whose own horizontal bar stays off).
   */
  @NotNull
  JScrollBar createHorizontalScrollBar() {
    JScrollBar scrollBar = new JScrollBar(Adjustable.HORIZONTAL);
    scrollBar.addAdjustmentListener(event -> {
      if (!syncingScrollBar) {
        applyScrollValue(event.getValue());
      }
    });
    horizontalScrollBar = scrollBar;
    syncScrollBar();
    return scrollBar;
  }

  void setTree(@NotNull FlameNode tree) {
    root = tree;
    rowCount = ProfilerTimeline.treeDepth(tree);
    usPerPixel = 0;
    viewStartUs = tree.startUs();
    selected = null;
    clearSpanSelection();
    clearCurveSelection();
    selectedEvent = null;
    selectionListener.accept(List.of());
    syncScrollBar();
    revalidate();
    repaint();
    fireViewChanged();
  }

  /**
   * Swaps in a reloaded tree (a finer or shifted window over the same
   * session) WITHOUT touching the view; the selected run carries over when
   * the new tree still holds a node with the same bounds and symbol.
   */
  void updateTree(@NotNull FlameNode tree) {
    FlameNode previousSelection = selected;
    root = tree;
    rowCount = ProfilerTimeline.treeDepth(tree);
    selected = previousSelection == null ? null : reselect(previousSelection);
    if (previousSelection != null && selected == null) {
      selectionListener.accept(List.of());
    }
    revalidate();
    repaint();
  }

  /** The new tree's node matching the old selection's bounds and symbol, or null. */
  @Nullable
  private FlameNode reselect(FlameNode old) {
    long midUs = (old.startUs() + old.endUs()) / 2;
    FlameNode candidate = null;
    FlameNode node = root;
    while (node != null) {
      FlameNode next = null;
      for (FlameNode child : node.children()) {
        if (midUs >= child.startUs() && midUs < child.endUs()) {
          next = child;
          break;
        }
      }
      if (next != null && next.startUs() == old.startUs() && next.endUs() == old.endUs()
          && sameSymbol(next, old)) {
        candidate = next;
      }
      node = next;
    }
    return candidate;
  }

  private static boolean sameSymbol(FlameNode left, FlameNode right) {
    String a = left.frame() == null ? null : left.frame().symbol();
    String b = right.frame() == null ? null : right.frame().symbol();
    return Objects.equals(a, b);
  }

  void setViewListener(@Nullable ViewListener listener) {
    viewListener = listener;
    fireViewChanged();
  }

  /** Shows [startUs, endUs]; a span covering the whole session falls back to fit. */
  void setView(long startUs, long endUs) {
    long spanUs = Math.max(endUs - startUs, 1);
    int width = Math.max(1, getWidth());
    if (spanUs >= root.durationUs()) {
      usPerPixel = 0;
      viewStartUs = root.startUs();
    }
    else {
      usPerPixel = Math.max(spanUs / (double)width, MIN_US_PER_PIXEL);
      setViewStart(startUs);
    }
    syncScrollBar();
    repaint();
    fireViewChanged();
  }

  private void fireViewChanged() {
    if (viewListener == null) return;
    boolean wholeSession = usPerPixel <= 0;
    long visibleUs = wholeSession
                     ? Math.max(root.durationUs(), 1)
                     : (long)Math.ceil(Math.max(1, getWidth()) * usPerPixel);
    viewListener.viewChanged(viewLeftUs(), visibleUs, wholeSession);
  }

  void setLanes(@NotNull List<MarkerLane> lanes) {
    this.lanes = lanes;
    if (selectedSpan != null) {
      clearSpanSelection();
      selectionListener.accept(List.of());
    }
    revalidate();
    repaint();
  }

  void setCurveLanes(@NotNull List<CurveLane> lanes) {
    curveLanes = lanes;
    clearCurveSelection();
    revalidate();
    repaint();
  }

  /** Time-ordered instant marks for the events row; empty hides it. */
  void setEvents(@NotNull List<TimeEvent> events) {
    this.events = events;
    selectedEvent = null;
    revalidate();
    repaint();
  }

  private void clearSpanSelection() {
    selectedSpan = null;
    selectedSpanLane = null;
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D)graphics.create();
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setFont(JBUI.Fonts.smallFont());
      Rectangle clip = g.getClipBounds();
      if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
      paintRuler(g, clip);
      paintCurves(g, clip);
      paintEvents(g, clip);
      paintLanes(g, clip);
      for (FlameNode child : root.children()) {
        paintNode(g, child, 0, clip);
      }
    }
    finally {
      g.dispose();
    }
  }

  private void paintNode(Graphics2D g, FlameNode node, int row, Rectangle clip) {
    int x0 = xOf(node.startUs());
    int x1 = Math.max(xOf(node.endUs()), x0 + 1);
    if (x1 <= clip.x || x0 >= clip.x + clip.width) return;
    int y = chartTop() + row * rowHeight();
    if (y >= clip.y + clip.height) return;

    if (!node.idle() && y + rowHeight() > clip.y) {
      paintBox(g, node, x0, y, x1 - x0);
    }
    if (x1 - x0 <= 1) return; // the callees share this pixel column - nothing more to show
    for (FlameNode child : node.children()) {
      paintNode(g, child, row + 1, clip);
    }
  }

  private void paintBox(Graphics2D g, FlameNode node, int x, int y, int width) {
    int height = rowHeight() - 1;
    g.setColor(colorOf(node));
    g.fillRect(x, y, width, height);
    if (width > 2) {
      g.setColor(BOX_BORDER);
      g.drawRect(x, y, width - 1, height - 1);
    }
    if (node == selected) {
      paintSelection(g, x, y, width, height);
    }

    String symbol = node.frame() == null ? "" : node.frame().symbol();
    if (!symbol.isEmpty() && width > JBUI.scale(30)) {
      Shape outerClip = g.getClip();
      g.clipRect(x + 2, y, width - 4, height);
      g.setColor(BOX_TEXT);
      FontMetrics metrics = g.getFontMetrics();
      g.drawString(symbol, x + JBUI.scale(4), y + (height + metrics.getAscent() - metrics.getDescent()) / 2);
      g.setClip(outerClip);
    }
  }

  /** The selected run: a thick accent frame with an inner contrast line, widened so even a sliver stays findable. */
  private static void paintSelection(Graphics2D g, int x, int y, int width, int height) {
    int frameWidth = Math.max(width, JBUI.scale(3));
    g.setColor(SELECTION);
    g.drawRect(x, y, frameWidth - 1, height - 1);
    g.drawRect(x + 1, y + 1, frameWidth - 3, height - 3);
    if (frameWidth > JBUI.scale(6)) {
      g.setColor(SELECTION_INNER);
      g.drawRect(x + 2, y + 2, frameWidth - 5, height - 5);
    }
  }

  private void paintRuler(Graphics2D g, Rectangle clip) {
    long stepUs = niceStepUs(scale() * JBUI.scale(80));
    int height = rulerHeight();
    g.setColor(RULER_TICK);
    g.drawLine(clip.x, height - 1, clip.x + clip.width, height - 1);

    // ticks anchor to the CAPTURE's zero, not the thread's first sample, so
    // the axis matches the times shown in the run details and the timeline
    long firstTickUs = Math.max(0, timeAt(clip.x)) / stepUs * stepUs;
    for (long tickUs = firstTickUs; ; tickUs += stepUs) {
      int x = xOf(tickUs);
      if (x > clip.x + clip.width) break;
      g.setColor(RULER_TICK);
      g.drawLine(x, height - JBUI.scale(5), x, height - 1);
      g.setColor(RULER_TEXT);
      g.drawString(formatTick(tickUs, stepUs), x + JBUI.scale(3), height - JBUI.scale(6));
    }
  }

  /** The curve bands between the ruler and the marker lanes, one per lane, each scaled to its own peak. */
  private void paintCurves(Graphics2D g, Rectangle clip) {
    FontMetrics metrics = g.getFontMetrics();
    for (int index = 0; index < curveLanes.size(); index++) {
      CurveLane lane = curveLanes.get(index);
      int top = curveLaneTop(index);
      int height = curveLaneHeight(lane) - 1;
      paintCurve(g, lane, top, height, clip);
      if (selectedCurve != null && selectedCurve.lane() == lane) {
        paintCurveSelection(g, selectedCurve, top, height);
      }

      g.setColor(RULER_TEXT);
      String label = lane.name() + " — peak " + formatBytes(lane.peak());
      g.drawString(label, JBUI.scale(4), top + metrics.getAscent() + JBUI.scale(2));
      // the divider doubles as the band's resize grip
      g.setColor(RULER_TICK);
      g.drawLine(clip.x, top + height, clip.x + clip.width, top + height);
    }
  }

  /** An accent line at the CLICKED instant with a dot at the value holding there. */
  private void paintCurveSelection(Graphics2D g, CurveSelection selection, int top, int height) {
    CurveLane lane = selection.lane();
    if (lane.peak() <= 0) return;
    int x = xOf(selection.instantUs());
    int dot = JBUI.scale(3);
    double value = selection.lastChange().value();
    int valueY = top + height - (int)Math.ceil(value / lane.peak() * (height - JBUI.scale(2)));
    g.setColor(SELECTION);
    g.drawLine(x, top, x, top + height - 1);
    g.fillOval(x - dot, valueY - dot, dot * 2, dot * 2);
  }

  /**
   * Step semantics: a sample's value holds until the next sample. Sub-pixel
   * steps coalesce keeping the tallest, so allocation spikes stay visible at
   * any zoom; the last sample's value holds to the right edge.
   */
  private void paintCurve(Graphics2D g, CurveLane lane, int top, int height, Rectangle clip) {
    List<CurvePoint> points = lane.points();
    if (points.isEmpty() || lane.peak() <= 0) return;
    g.setColor(lane.color());
    int baseline = top + height;
    int maxBarHeight = height - JBUI.scale(2);
    int clipRight = clip.x + clip.width;

    int i = lastIndexAtOrBefore(points, timeAt(clip.x));
    while (i < points.size()) {
      int x0 = xOf(points.get(i).timeUs());
      if (x0 > clipRight) break;
      double value = points.get(i).value();
      int j = i + 1;
      int x1 = j < points.size() ? xOf(points.get(j).timeUs()) : clipRight;
      while (j < points.size() && x1 <= x0 + 1) {
        value = Math.max(value, points.get(j).value());
        j++;
        x1 = j < points.size() ? xOf(points.get(j).timeUs()) : clipRight;
      }
      int barHeight = (int)Math.ceil(value / lane.peak() * maxBarHeight);
      if (barHeight > 0) {
        g.fillRect(Math.max(x0, clip.x), baseline - barHeight, Math.max(1, x1 - Math.max(x0, clip.x)), barHeight);
      }
      i = j;
    }
  }

  /** The greatest index whose time is at or before {@code timeUs}; 0 when all lie after it. */
  private static int lastIndexAtOrBefore(List<CurvePoint> points, long timeUs) {
    int low = 0;
    int high = points.size() - 1;
    int result = 0;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (points.get(middle).timeUs() <= timeUs) {
        result = middle;
        low = middle + 1;
      }
      else {
        high = middle - 1;
      }
    }
    return result;
  }

  /** The events row: one small diamond per mark, in the event's own color when it sent one. */
  private void paintEvents(Graphics2D g, Rectangle clip) {
    if (events.isEmpty()) return;
    int y = curvesBottom();
    int height = rowHeight() - 1;
    int centerY = y + height / 2;
    int radius = JBUI.scale(3);

    int from = firstEventAtOrAfter(timeAt(clip.x) - 1);
    for (int i = from; i < events.size(); i++) {
      TimeEvent event = events.get(i);
      int x = xOf(event.timeUs());
      if (x > clip.x + clip.width) break;
      g.setColor(event.color() != null ? event.color() : EVENT_MARK);
      g.fillOval(x - radius, centerY - radius, radius * 2, radius * 2);
      if (event == selectedEvent) {
        g.setColor(SELECTION);
        g.drawOval(x - radius - 2, centerY - radius - 2, radius * 2 + 4, radius * 2 + 4);
      }
    }

    FontMetrics metrics = g.getFontMetrics();
    g.setColor(RULER_TEXT);
    String label = HaxeProfilerBundle.message("haxe.profiler.callchart.lane.events");
    g.drawString(label, JBUI.scale(4), y + (height + metrics.getAscent() - metrics.getDescent()) / 2);
    g.setColor(RULER_TICK);
    g.drawLine(clip.x, y + height, clip.x + clip.width, y + height);
  }

  /** The first event index whose time is at or after {@code timeUs}. */
  private int firstEventAtOrAfter(long timeUs) {
    int low = 0;
    int high = events.size() - 1;
    int result = events.size();
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (events.get(middle).timeUs() >= timeUs) {
        result = middle;
        high = middle - 1;
      }
      else {
        low = middle + 1;
      }
    }
    return result;
  }

  /** The mark within a few pixels of the point on the events row, or null. */
  @Nullable
  private TimeEvent eventAt(Point point) {
    if (events.isEmpty() || point.y < curvesBottom() || point.y >= lanesTop()) return null;
    int grip = JBUI.scale(4);
    TimeEvent best = null;
    int bestDistance = grip + 1;
    for (int i = Math.max(firstEventAtOrAfter(timeAt(point.x - grip)) - 1, 0); i < events.size(); i++) {
      int distance = Math.abs(xOf(events.get(i).timeUs()) - point.x);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = events.get(i);
      }
      if (xOf(events.get(i).timeUs()) > point.x + grip) break;
    }
    return best;
  }

  private void paintLanes(Graphics2D g, Rectangle clip) {
    FontMetrics metrics = g.getFontMetrics();
    for (int index = 0; index < lanes.size(); index++) {
      MarkerLane lane = lanes.get(index);
      int y = lanesTop() + index * rowHeight();
      int height = rowHeight() - 1;

      List<UsSpan> spans = lane.spans();
      for (int i = 0; i < spans.size(); i++) {
        UsSpan span = spans.get(i);
        int x0 = xOf(span.startUs());
        int x1 = Math.max(xOf(span.endUs()), x0 + 1);
        if (x1 <= clip.x || x0 >= clip.x + clip.width) continue;
        g.setColor(i % 2 == 0 ? lane.evenColor() : lane.oddColor());
        g.fillRect(x0, y, x1 - x0, height);
        if (span == selectedSpan && lane == selectedSpanLane) {
          paintSelection(g, x0, y, x1 - x0, height);
        }
      }

      g.setColor(RULER_TEXT);
      g.drawString(lane.name(), JBUI.scale(4), y + (height + metrics.getAscent() - metrics.getDescent()) / 2);
      g.setColor(RULER_TICK);
      g.drawLine(clip.x, y + height, clip.x + clip.width, y + height);
    }
  }

  private static Color colorOf(FlameNode node) {
    StackFrame frame = node.frame();
    int index = frame == null ? BOX_COLORS.length - 1 : Math.floorMod(frame.symbol().hashCode(), BOX_COLORS.length);
    return BOX_COLORS[index];
  }

  @Override
  public @Nullable String getToolTipText(@NotNull MouseEvent event) {
    TimeEvent mark = eventAt(event.getPoint());
    if (mark != null) {
      return mark.text();
    }
    CurveLane curveLane = curveLaneAt(event.getPoint());
    if (curveLane != null && !curveLane.points().isEmpty()) {
      int index = lastIndexAtOrBefore(curveLane.points(), timeAt(event.getX()));
      return curveLane.name() + " — " + formatBytes(curveLane.points().get(index).value());
    }
    MarkerLane lane = laneAt(event.getPoint());
    if (lane != null) {
      UsSpan span = spanAt(lane, timeAt(event.getX()));
      return span == null ? null : lane.name() + " — " + formatUs(span.durationUs());
    }
    FlameNode node = nodeAt(event.getPoint());
    if (node == null || node.frame() == null) return null;
    return node.frame().symbol() + " — " + formatUs(node.durationUs()) + ", "
           + HaxeProfilerBundle.message("haxe.profiler.callchart.samples", node.samples());
  }

  private void navigateAt(Point point) {
    FlameNode node = nodeAt(point);
    StackFrame frame = node == null ? null : node.frame();
    if (frame != null) {
      navigator.accept(frame);
    }
  }

  /**
   * Selects the clicked run, marker-lane span or curve sample and reports
   * it; empty space, an idle filler or the gap between spans clears the
   * selection.
   */
  private void selectAt(Point point) {
    if (!events.isEmpty() && point.y >= curvesBottom() && point.y < lanesTop()) {
      TimeEvent event = eventAt(point);
      selected = null;
      clearSpanSelection();
      clearCurveSelection();
      selectedEvent = event;
      if (event != null) {
        eventSelectionListener.accept(event);
      }
      else {
        selectionListener.accept(List.of());
      }
      repaint();
      return;
    }

    CurveLane curveLane = curveLaneAt(point);
    if (curveLane != null) {
      selected = null;
      clearSpanSelection();
      long instantUs = timeAt(point.x);
      CurvePoint lastChange = lastChangeAt(curveLane, instantUs);
      if (lastChange == null) {
        // before the pool's first event there is no reading to select
        clearCurveSelection();
        selectionListener.accept(List.of());
      }
      else {
        selectedCurve = new CurveSelection(curveLane, instantUs, lastChange);
        curveSelectionListener.accept(selectedCurve);
      }
      repaint();
      return;
    }

    MarkerLane lane = laneAt(point);
    if (lane != null) {
      UsSpan span = spanAt(lane, timeAt(point.x));
      selected = null;
      clearCurveSelection();
      selectedSpan = span;
      selectedSpanLane = span == null ? null : lane;
      if (span != null) {
        spanSelectionListener.accept(lane, span);
      }
      else {
        selectionListener.accept(List.of());
      }
      repaint();
      return;
    }

    List<FlameNode> path = pathAt(point);
    selected = path.isEmpty() ? null : path.getLast();
    clearSpanSelection();
    clearCurveSelection();
    selectionListener.accept(path);
    repaint();
  }

  private void clearCurveSelection() {
    selectedCurve = null;
  }

  /** The sample whose value holds at {@code instantUs}; null before the lane's first sample. */
  @Nullable
  private static CurvePoint lastChangeAt(CurveLane lane, long instantUs) {
    if (lane.points().isEmpty()) return null;
    CurvePoint floor = lane.points().get(lastIndexAtOrBefore(lane.points(), instantUs));
    return floor.timeUs() <= instantUs ? floor : null;
  }

  private List<FlameNode> pathAt(Point point) {
    int row = chartRowAt(point);
    if (row < 0) return List.of();
    List<FlameNode> path = ProfilerTimeline.pathTo(root, timeAt(point.x), row + 1);
    // a shorter path means the clicked row is idle or below the stack there
    return path.size() == row + 1 ? path : List.of();
  }

  @Nullable
  private FlameNode nodeAt(Point point) {
    int row = chartRowAt(point);
    if (row < 0) return null;
    return ProfilerTimeline.nodeAt(root, timeAt(point.x), row + 1);
  }

  /** The chart row under the point, -1 outside the run area (ruler and lanes above, past the tree below). */
  private int chartRowAt(Point point) {
    if (point.y < chartTop()) return -1;
    int row = (point.y - chartTop()) / rowHeight();
    return row < rowCount ? row : -1;
  }

  @Nullable
  private MarkerLane laneAt(Point point) {
    if (point.y < lanesTop() || point.y >= chartTop()) return null;
    return lanes.get((point.y - lanesTop()) / rowHeight());
  }

  @Nullable
  private CurveLane curveLaneAt(Point point) {
    if (point.y < rulerHeight() || point.y >= curvesBottom()) return null;
    for (int index = 0; index < curveLanes.size(); index++) {
      if (point.y < curveLaneTop(index + 1)) return curveLanes.get(index);
    }
    return null;
  }

  /** Binary search over a lane's time-ordered spans; null between spans. */
  @Nullable
  private static UsSpan spanAt(MarkerLane lane, long timeUs) {
    List<UsSpan> spans = lane.spans();
    int low = 0;
    int high = spans.size() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      UsSpan span = spans.get(middle);
      if (timeUs < span.startUs()) {
        high = middle - 1;
      }
      else if (timeUs >= span.endUs()) {
        low = middle + 1;
      }
      else {
        return span;
      }
    }
    return null;
  }

  /** Ctrl+wheel zooms around the pointer, shift+wheel pans; anything else scrolls the pane vertically as usual. */
  private void onWheel(MouseWheelEvent event) {
    if (event.isControlDown()) {
      event.consume();
      zoomTo(scale() * Math.pow(ZOOM_STEP, event.getPreciseWheelRotation()), timeAt(event.getX()), event.getX());
      return;
    }
    if (event.isShiftDown() && usPerPixel > 0) {
      event.consume();
      int deltaPx = (int)Math.round(event.getPreciseWheelRotation() * getWidth() * 0.1);
      setViewStart(viewStartUs + (long)(deltaPx * usPerPixel));
      syncScrollBar();
      repaint();
      fireViewChanged();
      return;
    }
    Container parent = getParent();
    if (parent != null) {
      parent.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, parent));
    }
  }

  void zoomIn() {
    zoomAtCenter(1 / BUTTON_ZOOM_STEP);
  }

  void zoomOut() {
    zoomAtCenter(BUTTON_ZOOM_STEP);
  }

  /** Button zoom anchors on the selected run so it stays centered in frame; without a selection the view center holds. */
  private void zoomAtCenter(double factor) {
    Rectangle visible = getVisibleRect();
    int centerX = visible.x + visible.width / 2;
    long anchorUs = selected != null ? (selected.startUs() + selected.endUs()) / 2 : timeAt(centerX);
    zoomTo(scale() * factor, anchorUs, centerX);
  }

  /** Applies the target scale keeping {@code anchorUs} under pixel {@code mouseX}; zooming past the session falls back to fit. */
  private void zoomTo(double targetUsPerPixel, long anchorUs, int mouseX) {
    if (targetUsPerPixel >= fitScale(getWidth())) {
      usPerPixel = 0;
      viewStartUs = root.startUs();
    }
    else {
      usPerPixel = Math.max(targetUsPerPixel, MIN_US_PER_PIXEL);
      setViewStart(anchorUs - (long)(mouseX * usPerPixel));
    }
    syncScrollBar();
    repaint();
    fireViewChanged();
  }

  /** Moves the zoomed view's left edge, clamped so the view never leaves the session. */
  private void setViewStart(long startUs) {
    long visibleUs = (long)Math.ceil(Math.max(1, getWidth()) * usPerPixel);
    long maxStartUs = root.startUs() + Math.max(0, root.durationUs() - visibleUs);
    viewStartUs = Math.max(root.startUs(), Math.min(startUs, maxStartUs));
  }

  /** Mirrors the view into the scrollbar: proportional thumb, disabled in fit mode. */
  private void syncScrollBar() {
    JScrollBar scrollBar = horizontalScrollBar;
    if (scrollBar == null) return;
    syncingScrollBar = true;
    try {
      long durationUs = root.durationUs();
      if (usPerPixel <= 0 || durationUs <= 0) {
        scrollBar.setEnabled(false);
        scrollBar.setValues(0, SCROLL_RESOLUTION, 0, SCROLL_RESOLUTION);
        return;
      }
      long visibleUs = (long)Math.ceil(Math.max(1, getWidth()) * usPerPixel);
      int extent = (int)Math.min(SCROLL_RESOLUTION, Math.max(1, (long)(SCROLL_RESOLUTION * (double)visibleUs / durationUs)));
      int value = (int)Math.round((viewStartUs - root.startUs()) / (double)durationUs * SCROLL_RESOLUTION);
      scrollBar.setEnabled(extent < SCROLL_RESOLUTION);
      scrollBar.setValues(Math.min(value, SCROLL_RESOLUTION - extent), extent, 0, SCROLL_RESOLUTION);
      scrollBar.setUnitIncrement(Math.max(1, extent / 20));
      scrollBar.setBlockIncrement(Math.max(1, extent * 9 / 10));
    }
    finally {
      syncingScrollBar = false;
    }
  }

  private void applyScrollValue(int value) {
    if (usPerPixel <= 0) return;
    long offsetUs = (long)((double)value / SCROLL_RESOLUTION * root.durationUs());
    setViewStart(root.startUs() + offsetUs);
    repaint();
    fireViewChanged();
  }

  private int rowHeight() {
    return JBUI.scale(17);
  }

  private int rulerHeight() {
    return JBUI.scale(20);
  }

  private int curveLaneHeight(CurveLane lane) {
    return curveHeights.getOrDefault(lane.name(), JBUI.scale(40));
  }

  /** The y where the curve band at {@code index} starts. */
  private int curveLaneTop(int index) {
    int top = rulerHeight();
    for (int i = 0; i < index; i++) {
      top += curveLaneHeight(curveLanes.get(i));
    }
    return top;
  }

  /** Where the marker lanes start: below the ruler and the curve bands. */
  private int curvesBottom() {
    return curveLaneTop(curveLanes.size());
  }

  private int eventsRowHeight() {
    return events.isEmpty() ? 0 : rowHeight();
  }

  /** Where the marker lanes start: below the events row (present only when the capture has events). */
  private int lanesTop() {
    return curvesBottom() + eventsRowHeight();
  }

  /** Where the run rows start: below the ruler, curve bands, events row and marker lanes. */
  private int chartTop() {
    return lanesTop() + lanes.size() * rowHeight();
  }

  /** The curve band whose bottom divider is under {@code y}; -1 when none is. */
  private int curveDividerAt(int y) {
    int grip = JBUI.scale(3);
    for (int index = 0; index < curveLanes.size(); index++) {
      int bottom = curveLaneTop(index + 1);
      if (Math.abs(y - bottom) <= grip) return index;
    }
    return -1;
  }

  private void resizeCurveLane(CurveLane lane, int height) {
    int clamped = Math.max(JBUI.scale(20), Math.min(JBUI.scale(200), height));
    curveHeights.put(lane.name(), clamped);
    revalidate();
    repaint();
  }

  private double scale() {
    return usPerPixel > 0 ? usPerPixel : fitScale(getWidth());
  }

  private double fitScale(int width) {
    return Math.max(1, root.durationUs()) / (double)Math.max(1, width);
  }

  /** The time at the panel's left edge — the view start while zoomed, the tree start in fit mode. */
  private long viewLeftUs() {
    return usPerPixel > 0 ? viewStartUs : root.startUs();
  }

  /**
   * Clamped far outside any viewport: at deep zoom a session-distant time
   * maps to billions of pixels, and a raw int cast would wrap negative and
   * break the painters' culling. The clamp keeps ordering, so culling and
   * fill widths stay correct.
   */
  private int xOf(long timeUs) {
    double x = (timeUs - viewLeftUs()) / scale();
    return (int)Math.round(Math.max(-10_000_000, Math.min(10_000_000, x)));
  }

  private long timeAt(double x) {
    return viewLeftUs() + (long)(x * scale());
  }

  /** The smallest 1/2/5 x 10^k microsecond step at least minUs wide. */
  private static long niceStepUs(double minUs) {
    for (long magnitude = 1; ; magnitude *= 10) {
      for (long factor : STEP_FACTORS) {
        long step = factor * magnitude;
        if (step >= minUs) return step;
      }
    }
  }

  /** Tick label with the precision the STEP demands — at 5 ms steps every tick inside one second must still differ. */
  private static String formatTick(long us, long stepUs) {
    if (stepUs >= 1_000_000) return us / 1_000_000 + " s";
    if (stepUs >= 1000) return us / 1000 + " ms";
    int decimals = 4 - Long.toString(stepUs).length();
    return String.format(Locale.ROOT, "%." + decimals + "f ms", us / 1000.0);
  }

  /**
   * "3288.617 ms – 3288.813 ms (196 µs)": endpoints in the ruler's
   * milliseconds — second-rounded endpoints say nothing about a sub-ms span.
   */
  static String formatRange(long startUs, long endUs) {
    long durationUs = endUs - startUs;
    String pattern = "%." + rangeDecimals(durationUs) + "f ms";
    return String.format(Locale.ROOT, pattern, startUs / 1000.0) + " – "
           + String.format(Locale.ROOT, pattern, endUs / 1000.0)
           + " (" + formatUs(durationUs) + ")";
  }

  /** Enough fractional digits that the two endpoints visibly differ across the span. */
  private static int rangeDecimals(long durationUs) {
    if (durationUs < 1000) return 3;
    if (durationUs < 10_000) return 2;
    if (durationUs < 100_000) return 1;
    return 0;
  }

  /** One instant on the chart's axis, in the ruler's milliseconds: "3288.617 ms". */
  static String formatInstant(long us) {
    return String.format(Locale.ROOT, "%.3f ms", us / 1000.0);
  }

  /** 0 B, 512 B, 34.5 KB, 3.2 MB, 1.5 GB - the shortest form for the magnitude. */
  static String formatBytes(double bytes) {
    if (bytes < 1024) return (long)bytes + " B";
    if (bytes < 1024 * 1024) return trimTrailingZero(bytes / 1024) + " KB";
    if (bytes < 1024L * 1024 * 1024) return trimTrailingZero(bytes / (1024 * 1024)) + " MB";
    return trimTrailingZero(bytes / (1024L * 1024 * 1024)) + " GB";
  }

  /** 0, 250 us, 1.5 ms, 320 ms, 4.2 s - the shortest form for the magnitude. */
  static String formatUs(long us) {
    if (us == 0) return "0";
    if (us < 1000) return us + " µs";
    if (us < 1_000_000) return trimTrailingZero(us / 1000.0) + " ms";
    return trimTrailingZero(us / 1_000_000.0) + " s";
  }

  private static String trimTrailingZero(double value) {
    String text = String.format(Locale.ROOT, "%.1f", value);
    return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
  }

  @Override
  public Dimension getPreferredSize() {
    // width always tracks the viewport (the horizontal axis is virtual); only the height is real
    return new Dimension(0, chartTop() + rowCount * rowHeight());
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return rowHeight();
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return orientation == SwingConstants.HORIZONTAL ? visible.width : visible.height;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
  }
}
