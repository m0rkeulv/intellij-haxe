package com.intellij.plugins.haxe.ide.formatter.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * The hxformat tab: whether a project's own hxformat.json overrides the
 * scheme's Haxe formatting per file (see HaxeHxformatSettingsModifier).
 */
public class HaxeHxformatCodeStylePanel extends CodeStyleAbstractPanel {

  private final JPanel panel;
  private final JBCheckBox useProjectConfig =
    new JBCheckBox(HaxeBundle.message("hxformat.panel.use.project.config"));

  protected HaxeHxformatCodeStylePanel(CodeStyleSettings settings) {
    super(settings);
    JBLabel description = new JBLabel(HaxeBundle.message("hxformat.panel.description"));
    description.setComponentStyle(UIUtil.ComponentStyle.SMALL);
    description.setForeground(UIUtil.getContextHelpForeground());
    JPanel form = FormBuilder.createFormBuilder()
      .addComponent(useProjectConfig)
      .addComponent(description)
      .getPanel();
    form.setBorder(JBUI.Borders.empty(10));
    panel = new JPanel(new BorderLayout());
    panel.add(form, BorderLayout.NORTH);
  }

  @Override
  protected String getTabTitle() {
    return HaxeBundle.message("hxformat.panel.tab.title");
  }

  @Override
  public void apply(@NotNull CodeStyleSettings settings) {
    settings.getCustomSettings(HaxeCodeStyleSettings.class).USE_PROJECT_HXFORMAT = useProjectConfig.isSelected();
  }

  @Override
  public boolean isModified(CodeStyleSettings settings) {
    return settings.getCustomSettings(HaxeCodeStyleSettings.class).USE_PROJECT_HXFORMAT != useProjectConfig.isSelected();
  }

  @Override
  protected void resetImpl(@NotNull CodeStyleSettings settings) {
    useProjectConfig.setSelected(settings.getCustomSettings(HaxeCodeStyleSettings.class).USE_PROJECT_HXFORMAT);
  }

  @Override
  public @Nullable JComponent getPanel() {
    return panel;
  }

  @Override
  protected int getRightMargin() {
    return 0;
  }

  @Override
  protected @Nullable EditorHighlighter createHighlighter(@NotNull EditorColorsScheme scheme) {
    return null;
  }

  @Override
  protected @NotNull FileType getFileType() {
    return HaxeFileType.INSTANCE;
  }

  @Override
  protected @Nullable String getPreviewText() {
    return null;
  }
}
