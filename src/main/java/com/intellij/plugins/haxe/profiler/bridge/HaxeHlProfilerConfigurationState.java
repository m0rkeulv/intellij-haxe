package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/** State of one "HashLink Profiler" configuration: its user-visible name and the sampling rate. */
public final class HaxeHlProfilerConfigurationState implements ProfilerConfigurationState {

  /** 10000/s is the vshaxe precedent and gives ~0.1 ms precision. */
  public static final int DEFAULT_SAMPLES_PER_SECOND = 10000;

  private String displayName;
  private int samplesPerSecond;

  public HaxeHlProfilerConfigurationState(@NotNull String displayName, int samplesPerSecond) {
    this.displayName = displayName;
    this.samplesPerSecond = samplesPerSecond;
  }

  @Override
  public @NotNull String getConfigurationTypeId() {
    return HaxeHlProfilerConfigurationType.ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(@NotNull String name) {
    displayName = name;
  }

  public int getSamplesPerSecond() {
    return samplesPerSecond;
  }

  public void setSamplesPerSecond(int samplesPerSecond) {
    this.samplesPerSecond = samplesPerSecond > 0 ? samplesPerSecond : DEFAULT_SAMPLES_PER_SECOND;
  }
}
