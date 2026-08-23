package com.intellij.plugins.haxe.ide.formatter.settings;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * The Conditional Compilation tab of the Haxe code style: how INACTIVE
 * regions (every branch the current defines exclude - #if, #elseif and #else
 * alike) are treated by reformat. Its options span indentation, spacing and
 * line breaks at once, so they live in their own tab instead of Wrapping.
 */
public class HaxeConditionalCompilationPanel extends CodeStyleAbstractPanel {

  private final JPanel panel;
  private final JBCheckBox formatInactive =
    new JBCheckBox(HaxeBundle.message("haxe.codestyle.cc.format.inactive"));
  private final JBCheckBox alignInactive =
    new JBCheckBox(HaxeBundle.message("haxe.codestyle.cc.align.inactive"));

  protected HaxeConditionalCompilationPanel(CodeStyleSettings settings) {
    super(settings);
    JPanel form = FormBuilder.createFormBuilder()
      .addComponent(new TitledSeparator(HaxeBundle.message("haxe.codestyle.cc.inactive.title")))
      .addComponent(formatInactive)
      .addComponent(alignInactive)
      .getPanel();
    form.setBorder(JBUI.Borders.empty(10));
    JPanel options = new JPanel(new BorderLayout());
    options.add(form, BorderLayout.NORTH);
    panel = new JPanel(new BorderLayout());
    panel.add(options, BorderLayout.WEST);
    if (getEditor() != null) {
      panel.add(getEditor().getComponent(), BorderLayout.CENTER);
    }
    installPreviewUpdater(formatInactive);
    installPreviewUpdater(alignInactive);
  }

  /** The preview reformats from the panel's settings clone - push edits into it live. */
  private void installPreviewUpdater(JBCheckBox checkBox) {
    checkBox.addActionListener(event -> {
      apply(getSettings());
      somethingChanged();
    });
  }

  @Override
  protected String getTabTitle() {
    return HaxeBundle.message("haxe.codestyle.cc.tab.title");
  }

  @Override
  public void apply(@NotNull CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    haxe.FORMAT_INACTIVE_BRANCHES = formatInactive.isSelected();
    haxe.ALIGN_INACTIVE_CONDITIONAL_BRANCHES = alignInactive.isSelected();
  }

  @Override
  public boolean isModified(CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    return haxe.FORMAT_INACTIVE_BRANCHES != formatInactive.isSelected()
           || haxe.ALIGN_INACTIVE_CONDITIONAL_BRANCHES != alignInactive.isSelected();
  }

  @Override
  protected void resetImpl(@NotNull CodeStyleSettings settings) {
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    formatInactive.setSelected(haxe.FORMAT_INACTIVE_BRANCHES);
    alignInactive.setSelected(haxe.ALIGN_INACTIVE_CONDITIONAL_BRANCHES);
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

  @Override
  protected @Nullable String getPreviewText() {
    return CONDITIONAL_CODE_SAMPLE;
  }

  // deliberately messy inactive branches: with formatting ON the preview
  // cleans them up, with it OFF they stay exactly like this
  @org.intellij.lang.annotations.Language("Haxe")
  private static final String CONDITIONAL_CODE_SAMPLE = """
    class Main {
         #if my_flag
        static   function helper( value:Int ):Void {
        trace(   "helper"  ,value+1 );
        }
         #end

         static function main() {
              #if js
             trace(   "js"  ,1+2 );
              var point=  {x:1,y:2};
              #elseif neko
               trace( "neko" );
              #else
             trace("other target"   );
              #end
              var mode = #if debug "debug" #else "release"   #end;
         }
    }
    """;
}
