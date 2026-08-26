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
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A Chrome/Android-style call chart over one thread's time-ordered flame
 * tree ({@link ProfilerTimeline#flameTree}): x = time under a ruler, one row
 * per stack depth with the outermost call on top. Marker lanes between the
 * ruler and the runs carry named span sources on the same axis (frames, GC
 * collections — any provider). Runs too narrow for a pixel paint as
 * 1&nbsp;px slivers so activity stays visible at any zoom; idle spans stay
 * unpainted. Ctrl+wheel zooms around the pointer, plain wheel keeps
 * scrolling the pane; a click selects a run and reports its call chain to
 * the selection listener, double-click opens its Haxe source.
 */
final class HaxeCallChartPanel extends JComponent implements Scrollable {

  /**
   * A named row of spans above the chart; even/odd colors alternate so span
   * boundaries stay visible. {@code spanKind} names ONE span for the detail
   * view ("Frame", "Major GC collection").
   */
  record MarkerLane(@NotNull String name, @NotNull String spanKind, @NotNull List<UsSpan> spans,
                    @NotNull Color evenColor, @NotNull Color oddColor) {
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
  private static final long[] STEP_FACTORS = {1, 2, 5};
  private static final double ZOOM_STEP = 1.3;
  /** One zoom-button press doubles or halves the scale. */
  private static final double BUTTON_ZOOM_STEP = 2.0;
  /** Zoom-in floor: a 100 us sampling period still spans several hundred px. */
  private static final double MIN_US_PER_PIXEL = 0.25;

  private final Consumer<StackFrame> navigator;
  /** Told the selected run's call chain, outermost first; an empty list on deselection. */
  private final Consumer<List<FlameNode>> selectionListener;
  /** Told the selected marker-lane span; deselection goes through the run listener's empty list. */
  private final BiConsumer<MarkerLane, UsSpan> spanSelectionListener;
  private FlameNode root = new FlameNode(null, 0, 0, 0, List.of(), false);
  private List<MarkerLane> lanes = List.of();
  private @Nullable FlameNode selected;
  private @Nullable UsSpan selectedSpan;
  private @Nullable MarkerLane selectedSpanLane;
  private int rowCount;
  /** Microseconds one pixel covers; 0 = fit the whole capture to the viewport. */
  private double usPerPixel;

  HaxeCallChartPanel(@NotNull Consumer<StackFrame> navigator,
                     @NotNull Consumer<List<FlameNode>> selectionListener,
                     @NotNull BiConsumer<MarkerLane, UsSpan> spanSelectionListener) {
    this.navigator = navigator;
    this.selectionListener = selectionListener;
    this.spanSelectionListener = spanSelectionListener;
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
    });
    addMouseWheelListener(this::onWheel);
  }

  void setTree(@NotNull FlameNode tree) {
    root = tree;
    rowCount = ProfilerTimeline.treeDepth(tree);
    usPerPixel = 0;
    selected = null;
    clearSpanSelection();
    selectionListener.accept(List.of());
    revalidate();
    repaint();
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

  private void paintLanes(Graphics2D g, Rectangle clip) {
    FontMetrics metrics = g.getFontMetrics();
    for (int index = 0; index < lanes.size(); index++) {
      MarkerLane lane = lanes.get(index);
      int y = rulerHeight() + index * rowHeight();
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

      // the label follows the scrolled view so it stays readable at any position
      g.setColor(RULER_TEXT);
      int labelX = getVisibleRect().x + JBUI.scale(4);
      g.drawString(lane.name(), labelX, y + (height + metrics.getAscent() - metrics.getDescent()) / 2);
    }
  }

  private static Color colorOf(FlameNode node) {
    StackFrame frame = node.frame();
    int index = frame == null ? BOX_COLORS.length - 1 : Math.floorMod(frame.symbol().hashCode(), BOX_COLORS.length);
    return BOX_COLORS[index];
  }

  @Override
  public @Nullable String getToolTipText(@NotNull MouseEvent event) {
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
   * Selects the clicked run or marker-lane span and reports it; empty space,
   * an idle filler or the gap between spans clears the selection.
   */
  private void selectAt(Point point) {
    MarkerLane lane = laneAt(point);
    if (lane != null) {
      UsSpan span = spanAt(lane, timeAt(point.x));
      selected = null;
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
    selectionListener.accept(path);
    repaint();
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
    if (point.y < rulerHeight() || point.y >= chartTop()) return null;
    return lanes.get((point.y - rulerHeight()) / rowHeight());
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

  /** Ctrl+wheel zooms around the pointer; anything else scrolls the pane as usual. */
  private void onWheel(MouseWheelEvent event) {
    if (!event.isControlDown()) {
      Container parent = getParent();
      if (parent != null) {
        parent.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, parent));
      }
      return;
    }
    event.consume();
    zoomTo(scale() * Math.pow(ZOOM_STEP, event.getPreciseWheelRotation()), timeAt(event.getX()), event.getX());
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

  private void zoomTo(double targetUsPerPixel, long anchorUs, int mouseX) {
    JViewport viewport = (JViewport)SwingUtilities.getAncestorOfClass(JViewport.class, this);
    if (viewport == null) return;
    if (targetUsPerPixel >= fitScale(viewport.getWidth())) {
      usPerPixel = 0;
      revalidate();
      repaint();
      return;
    }
    usPerPixel = Math.max(targetUsPerPixel, MIN_US_PER_PIXEL);

    // keep the time under the pointer in place; the viewport clamps view
    // positions against its OLD extent until the revalidate lays out
    int mouseInViewport = mouseX - viewport.getViewPosition().x;
    double appliedScale = usPerPixel;
    revalidate();
    SwingUtilities.invokeLater(() -> {
      int anchorX = (int)Math.round((anchorUs - root.startUs()) / appliedScale);
      viewport.setViewPosition(new Point(Math.max(0, anchorX - mouseInViewport), viewport.getViewPosition().y));
      repaint();
    });
  }

  private int rowHeight() {
    return JBUI.scale(17);
  }

  private int rulerHeight() {
    return JBUI.scale(20);
  }

  /** Where the run rows start: below the ruler and the marker lanes. */
  private int chartTop() {
    return rulerHeight() + lanes.size() * rowHeight();
  }

  private double scale() {
    return usPerPixel > 0 ? usPerPixel : fitScale(getWidth());
  }

  private double fitScale(int width) {
    return Math.max(1, root.durationUs()) / (double)Math.max(1, width);
  }

  private int xOf(long timeUs) {
    return (int)Math.round((timeUs - root.startUs()) / scale());
  }

  private long timeAt(double x) {
    return root.startUs() + (long)(x * scale());
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
    int width = usPerPixel > 0 ? (int)Math.ceil(root.durationUs() / usPerPixel) : 0;
    return new Dimension(width, chartTop() + rowCount * rowHeight());
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
    return usPerPixel <= 0;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
  }
}
