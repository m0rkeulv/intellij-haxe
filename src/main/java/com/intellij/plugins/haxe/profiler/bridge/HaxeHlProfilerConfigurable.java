package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/** Settings form of one HashLink profiler configuration: the sampling rate. */
final class HaxeHlProfilerConfigurable implements UnnamedConfigurable {

  private final HaxeHlProfilerConfigurationState state;
  private final JBTextField samplesField = new JBTextField(10);

  HaxeHlProfilerConfigurable(@NotNull HaxeHlProfilerConfigurationState state) {
    this.state = state;
  }

  @Override
  public @Nullable JComponent createComponent() {
    JBLabel label = new JBLabel(HaxeProfilerBundle.message("haxe.profiler.samples.label"));
    return FormBuilder.createFormBuilder()
      .addLabeledComponent(label, samplesField)
      .getPanel();
  }

  @Override
  public boolean isModified() {
    return parsedSamples() != state.getSamplesPerSecond();
  }

  @Override
  public void apply() {
    state.setSamplesPerSecond(parsedSamples());
  }

  @Override
  public void reset() {
    samplesField.setText(String.valueOf(state.getSamplesPerSecond()));
  }

  /** A non-numeric entry falls back to the stored value rather than failing apply. */
  private int parsedSamples() {
    try {
      return Integer.parseInt(samplesField.getText().trim());
    }
    catch (NumberFormatException e) {
      return state.getSamplesPerSecond();
    }
  }
}
