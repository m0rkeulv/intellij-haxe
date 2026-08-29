package com.intellij.plugins.haxe.profiler.bridge.tracy;

import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/** Settings form of one "hxcpp Tracy" configuration: compression level and the feature toggles. Layout in the bound .form. */
final class HaxeHxcppTracyProfilerConfigurable implements UnnamedConfigurable {

  private final HaxeHxcppTracyProfilerConfigurationState state;
  private JPanel panel;
  private JBTextField levelField;
  private JBCheckBox captureMemoryCheckbox;
  private JBCheckBox collectProcessCpuCheckbox;

  HaxeHxcppTracyProfilerConfigurable(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    this.state = state;
  }

  @Override
  public @Nullable JComponent createComponent() {
    return panel;
  }

  @Override
  public boolean isModified() {
    return parsedLevel() != state.getCompressionLevel()
           || captureMemoryCheckbox.isSelected() != state.isCaptureMemory()
           || collectProcessCpuCheckbox.isSelected() != state.isCollectProcessCpu();
  }

  @Override
  public void apply() {
    state.setCompressionLevel(parsedLevel());
    state.setCaptureMemory(captureMemoryCheckbox.isSelected());
    state.setCollectProcessCpu(collectProcessCpuCheckbox.isSelected());
  }

  @Override
  public void reset() {
    levelField.setText(String.valueOf(state.getCompressionLevel()));
    captureMemoryCheckbox.setSelected(state.isCaptureMemory());
    collectProcessCpuCheckbox.setSelected(state.isCollectProcessCpu());
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
