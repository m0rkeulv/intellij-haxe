package com.intellij.plugins.haxe.ide.formatter.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Base for the Haxe code style tabs: an options form on the left, the shared
 * preview editor on the right (absent when the tab has no preview text).
 * A subclass builds its form in its constructor and hands it to
 * {@link #initPanel}; option listeners call {@link #previewChanged} so the
 * preview reformats from the edited values immediately.
 */
abstract class HaxeOptionsPreviewPanelBase extends CodeStyleAbstractPanel {

  private JPanel panel;

  protected HaxeOptionsPreviewPanelBase(CodeStyleSettings settings) {
    super(settings);
  }

  /** Lays the options form out beside the preview editor. Call once, after the form's fields exist. */
  protected final void initPanel(JPanel form) {
    form.setBorder(JBUI.Borders.empty(10));
    JPanel options = new JPanel(new BorderLayout());
    options.add(form, BorderLayout.NORTH);
    panel = new JPanel(new BorderLayout());
    panel.add(options, BorderLayout.WEST);
    if (getEditor() != null) {
      panel.add(getEditor().getComponent(), BorderLayout.CENTER);
    }
  }

  /** The preview reformats from the panel's settings clone - push edits into it live. */
  protected final void previewChanged() {
    try {
      apply(getSettings());
    }
    catch (ConfigurationException ignored) {
      // a mid-edit value may not validate; the preview keeps the last good state
    }
    somethingChanged();
  }

  @Override
  public @Nullable JComponent getPanel() {
    return panel;
  }

  @Override
  protected int getRightMargin() {
    return 60;
  }

  @Override
  protected @Nullable EditorHighlighter createHighlighter(@NotNull EditorColorsScheme scheme) {
    return EditorHighlighterFactory.getInstance().createEditorHighlighter(getFileType(), scheme, null);
  }

  @Override
  protected @NotNull FileType getFileType() {
    return HaxeFileType.INSTANCE;
  }
}
