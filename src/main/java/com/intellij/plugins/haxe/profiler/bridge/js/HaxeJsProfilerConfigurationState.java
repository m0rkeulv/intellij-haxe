package com.intellij.plugins.haxe.profiler.bridge.js;

import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/**
 * State of one "JavaScript Profiler" configuration: its user-visible name
 * and V8's sampling interval in microseconds (CDP
 * {@code Profiler.setSamplingInterval}; 1000 is V8's own default).
 */
public final class HaxeJsProfilerConfigurationState implements ProfilerConfigurationState {

  static final int DEFAULT_SAMPLING_INTERVAL_US = 1000;

  private String displayName;
  private int samplingIntervalUs = DEFAULT_SAMPLING_INTERVAL_US;

  public HaxeJsProfilerConfigurationState(@NotNull String displayName) {
    this.displayName = displayName;
  }

  @Override
  public @NotNull String getConfigurationTypeId() {
    return HaxeJsProfilerConfigurationType.ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(@NotNull String name) {
    displayName = name;
  }

  public int getSamplingIntervalUs() {
    return samplingIntervalUs;
  }

  public void setSamplingIntervalUs(int intervalUs) {
    samplingIntervalUs = Math.max(100, Math.min(intervalUs, 1_000_000));
  }
}
