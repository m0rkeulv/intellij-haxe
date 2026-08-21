package com.intellij.plugins.haxe.v2.toolwindow.ui;

import com.intellij.util.ui.UIUtil;
import javax.swing.JTextPane;
import org.jetbrains.annotations.NotNull;

/**
 * Styling for the gray hint panes under a dialog's fields. A JTextPane (not a
 * label) so long hints WRAP instead of dictating the dialog's minimum width.
 */
final class HaxeDialogHints {

  private HaxeDialogHints() {
  }

  static void style(@NotNull JTextPane hint) {
    hint.setForeground(UIUtil.getContextHelpForeground());
    hint.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
    hint.setBorder(null);
  }
}
