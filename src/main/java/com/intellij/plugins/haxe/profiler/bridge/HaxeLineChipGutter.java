package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.TextAnnotationGutterProvider;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * The rounded per-line time chips in the editor gutter, styled after the
 * stock In-Editor Performance Hints (same theme keys, same two-tier
 * red/grey design). The platform's annotation column cannot round its
 * corners, so an invisible {@link TextAnnotationGutterProvider} only
 * RESERVES the column's width and a lightweight overlay child of the
 * gutter paints the chips over it — rounded rect, left-aligned text, an
 * HTML tooltip on hover.
 */
final class HaxeLineChipGutter {

  private static final Color HOT_BACKGROUND =
    JBColor.namedColor("LineProfiler.HotLine.labelBackground", new JBColor(0xFFE0E0, 0x593D41));
  private static final Color LINE_BACKGROUND =
    JBColor.namedColor("LineProfiler.Line.labelBackground", new JBColor(0xDFDFDF, 0x43474A));
  private static final Color HOT_FOREGROUND =
    JBColor.namedColor("LineProfiler.HotLine.foreground", new JBColor(0xC7222D, 0xFF5261));
  private static final Color LINE_FOREGROUND =
    JBColor.namedColor("LineProfiler.Line.foreground", new JBColor(0x616769, 0x787878));
  /** A line at or above this share of the session renders hot. */
  private static final int HOT_PERCENT = 5;

  private HaxeLineChipGutter() {
  }

  /** One editor's installed chips; uninstall removes both the overlay and the reserved column. */
  record Installed(@NotNull TextAnnotationGutterProvider widthHolder,
                   @NotNull JComponent overlay,
                   @NotNull EditorGutterComponentEx gutter,
                   @NotNull ComponentListener relayout) {

    void uninstall(@NotNull Editor editor) {
      gutter.removeComponentListener(relayout);
      gutter.remove(overlay);
      editor.getGutter().closeTextAnnotations(List.of(widthHolder));
      gutter.repaint();
    }
  }

  /** Installs the chip column on the editor, or null when the editor exposes no extended gutter. */
  @Nullable
  static Installed install(@NotNull Editor editor, @NotNull Map<Integer, HaxeLineTimes.LineTime> lines, long sessionUs) {
    if (!(editor instanceof EditorEx editorEx)) return null;
    EditorGutterComponentEx gutter = editorEx.getGutterComponentEx();

    ChipOverlay overlay = new ChipOverlay(editorEx, lines, sessionUs);
    TextAnnotationGutterProvider widthHolder = new WidthHolder(overlay.blankOfColumnWidth());
    editor.getGutter().registerTextAnnotation(widthHolder);

    ComponentListener relayout = new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent event) {
        overlay.setBounds(gutter.getAnnotationsAreaOffset(), 0, gutter.getAnnotationsAreaWidth(), gutter.getHeight());
      }
    };
    gutter.add(overlay);
    gutter.addComponentListener(relayout);
    overlay.setBounds(gutter.getAnnotationsAreaOffset(), 0, gutter.getAnnotationsAreaWidth(), gutter.getHeight());
    gutter.repaint();
    return new Installed(widthHolder, overlay, gutter, relayout);
  }

  /**
   * Reserves the annotation column at the chips' width and paints nothing:
   * one all-spaces line is enough — the column is as wide as its widest
   * line, and spaces leave the chips' canvas clean.
   */
  private static final class WidthHolder implements TextAnnotationGutterProvider {
    private final String blank;

    WidthHolder(String blank) {
      this.blank = blank;
    }

    @Override
    public @Nullable String getLineText(int line, Editor editor) {
      return line == 0 ? blank : null;
    }

    @Override
    public @Nullable String getToolTip(int line, Editor editor) {
      return null;
    }

    @Override
    public EditorFontType getStyle(int line, Editor editor) {
      return EditorFontType.PLAIN;
    }

    @Override
    public @Nullable ColorKey getColor(int line, Editor editor) {
      return null;
    }

    @Override
    public @Nullable Color getBgColor(int line, Editor editor) {
      return null;
    }

    @Override
    public List<AnAction> getPopupActions(int line, Editor editor) {
      return List.of();
    }

    @Override
    public void gutterClosed() {
    }
  }

  /** Paints the visible lines' chips and answers their hover tooltips. */
  private static final class ChipOverlay extends JComponent {
    private final EditorEx editor;
    private final Map<Integer, HaxeLineTimes.LineTime> lines;
    private final long sessionUs;

    ChipOverlay(EditorEx editor, Map<Integer, HaxeLineTimes.LineTime> lines, long sessionUs) {
      this.editor = editor;
      this.lines = lines;
      this.sessionUs = sessionUs;
      setOpaque(false);
      ToolTipManager.sharedInstance().registerComponent(this);
    }

    private Font chipFont() {
      return editor.getColorsScheme().getFont(EditorFontType.PLAIN);
    }

    /** Spaces wide enough for the widest chip, measured in the same font the chips use. */
    String blankOfColumnWidth() {
      FontMetrics metrics = getFontMetrics(chipFont());
      int widest = 0;
      for (HaxeLineTimes.LineTime time : lines.values()) {
        widest = Math.max(widest, metrics.stringWidth(HaxeCallChartPanel.formatUs(time.totalUs())));
      }
      int columnWidth = widest + 2 * textPadding() + JBUI.scale(4);
      int spaces = Math.max(1, (int)Math.ceil(columnWidth / (double)Math.max(metrics.charWidth(' '), 1)));
      return " ".repeat(spaces);
    }

    private static int textPadding() {
      return JBUI.scale(5);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D)graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(chipFont());
        Rectangle clip = g.getClipBounds();
        if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());

        int lineCount = editor.getDocument().getLineCount();
        int visual = editor.yToVisualLine(Math.max(clip.y, 0));
        int bottom = clip.y + clip.height;
        while (true) {
          int y = editor.visualLineToY(visual);
          if (y > bottom) break;
          int logical = editor.visualToLogicalPosition(new VisualPosition(visual, 0)).line;
          if (logical >= lineCount) break;
          HaxeLineTimes.LineTime time = lines.get(logical + 1);
          if (time != null) {
            paintChip(g, y, time);
          }
          visual++;
        }
      }
      finally {
        g.dispose();
      }
    }

    private void paintChip(Graphics2D g, int y, HaxeLineTimes.LineTime time) {
      boolean hot = percentOfSession(time) >= HOT_PERCENT;
      String text = HaxeCallChartPanel.formatUs(time.totalUs());
      FontMetrics metrics = g.getFontMetrics();
      int height = editor.getLineHeight() - JBUI.scale(2);
      int width = metrics.stringWidth(text) + 2 * textPadding();
      int arc = JBUI.scale(6);
      g.setColor(hot ? HOT_BACKGROUND : LINE_BACKGROUND);
      g.fillRoundRect(0, y + JBUI.scale(1), width, height, arc, arc);
      g.setColor(hot ? HOT_FOREGROUND : LINE_FOREGROUND);
      int baseline = y + JBUI.scale(1) + (height + metrics.getAscent() - metrics.getDescent()) / 2;
      g.drawString(text, textPadding(), baseline);
    }

    /**
     * Like the Java hints: "X% of all — Y% of <method>" when the line knows
     * its enclosing method (sampled captures); tracy's per-function lines
     * show the figures instead.
     */
    @Override
    public @Nullable String getToolTipText(@NotNull MouseEvent event) {
      int logical = editor.visualToLogicalPosition(new VisualPosition(editor.yToVisualLine(event.getY()), 0)).line;
      HaxeLineTimes.LineTime time = lines.get(logical + 1);
      if (time == null) return null;
      String title = HaxeProfilerBundle.message("haxe.profiler.hints.tooltip.title");
      String detail;
      if (time.enclosing() != null && time.enclosingTotalUs() > 0) {
        String method = time.enclosing().substring(time.enclosing().lastIndexOf('.') + 1);
        detail = HaxeProfilerBundle.message("haxe.profiler.hints.tooltip.of.method",
                                            percentOfSession(time),
                                            time.totalUs() * 100 / time.enclosingTotalUs(),
                                            method);
      }
      else {
        detail = HaxeProfilerBundle.message("haxe.profiler.hints.tooltip.detail",
                                            percentOfSession(time),
                                            HaxeCallChartPanel.formatUs(time.totalUs()),
                                            HaxeCallChartPanel.formatUs(time.selfUs()));
      }
      return XmlStringUtil.wrapInHtml(title + "<br/>" + detail);
    }

    private long percentOfSession(HaxeLineTimes.LineTime time) {
      return time.totalUs() * 100 / sessionUs;
    }
  }
}
