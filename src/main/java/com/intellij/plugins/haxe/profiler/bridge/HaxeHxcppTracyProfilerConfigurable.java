package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/** Settings form of one "hxcpp Tracy" configuration: the session file's compression level. */
final class HaxeHxcppTracyProfilerConfigurable implements UnnamedConfigurable {

  private final HaxeHxcppTracyProfilerConfigurationState state;
  private final JBTextField levelField = new JBTextField(4);

  HaxeHxcppTracyProfilerConfigurable(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    this.state = state;
  }

  @Override
  public @Nullable JComponent createComponent() {
    JBLabel label = new JBLabel(HaxeProfilerBundle.message("haxe.profiler.compression.label"));
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(label, levelField)
      .addComponent(new JBLabel(HaxeProfilerBundle.message("haxe.profiler.compression.note")))
      .addComponent(new JBLabel(HaxeProfilerBundle.message("haxe.profiler.hxcpp.tracy.settings.note")))
      .getPanel();
  }

  @Override
  public boolean isModified() {
    return parsedLevel() != state.getCompressionLevel();
  }

  @Override
  public void apply() {
    state.setCompressionLevel(parsedLevel());
  }

  @Override
  public void reset() {
    levelField.setText(String.valueOf(state.getCompressionLevel()));
  }

  /** A non-numeric entry falls back to the stored value rather than failing apply. */
  private int parsedLevel() {
    try {
      return Integer.parseInt(levelField.getText().trim());
    }
    catch (NumberFormatException e) {
      return state.getCompressionLevel();
    }
  }
}
