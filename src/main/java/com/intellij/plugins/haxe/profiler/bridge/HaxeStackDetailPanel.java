package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JPanel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Detail view of a point in the capture: header lines (the first in bold)
 * above the call stack, leaf frame first, double-click navigating to a
 * frame's Haxe source — the Call Chart's detail pane for selected runs and
 * marker-lane spans.
 */
final class HaxeStackDetailPanel extends BorderLayoutPanel {
  private final Project project;
  private final JPanel header = new JPanel();
  private final DefaultListModel<String> rows = new DefaultListModel<>();
  private final JBList<String> list = new JBList<>(rows);
  private List<StackFrame> leafFirstFrames = List.of();

  HaxeStackDetailPanel(@NotNull Project project) {
    this.project = project;
    header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
    header.setBorder(JBUI.Borders.empty(4, 6));
    addToTop(header);
    addToCenter(new JBScrollPane(list));
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() == 2) {
          navigateToSelectedFrame();
        }
      }
    });
  }

  void showText(@NotNull String text) {
    showStack(List.of(text), List.of());
  }

  void showStack(@NotNull List<String> headerLines, @NotNull List<StackFrame> rootFirstStack) {
    header.removeAll();
    for (String line : headerLines) {
      JBLabel label = new JBLabel(line);
      if (header.getComponentCount() == 0) {
        label.setFont(JBFont.label().asBold());
      }
      header.add(label);
    }
    header.revalidate();
    header.repaint();

    rows.clear();
    leafFirstFrames = rootFirstStack.reversed();
    for (StackFrame frame : leafFirstFrames) {
      rows.addElement(rowText(frame));
    }
  }

  private static String rowText(StackFrame frame) {
    if (frame.file() == null) return frame.symbol();
    String fileName = frame.file().substring(frame.file().lastIndexOf('/') + 1);
    return frame.symbol() + "  (" + fileName + ":" + frame.line() + ")";
  }

  private void navigateToSelectedFrame() {
    int index = list.getSelectedIndex();
    if (index < 0 || index >= leafFirstFrames.size()) return;
    HaxeCallStackElement.navigateToFrame(project, leafFirstFrames.get(index));
  }
}
