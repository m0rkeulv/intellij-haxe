package com.intellij.plugins.haxe.profiler.bridge.hxcpp;

import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/** State of one "hxcpp Profiler" configuration: only its user-visible name — the hxcpp sampler ticks at a fixed 1 ms. */
public final class HaxeHxcppProfilerConfigurationState implements ProfilerConfigurationState {

  private String displayName;

  public HaxeHxcppProfilerConfigurationState(@NotNull String displayName) {
    this.displayName = displayName;
  }

  @Override
  public @NotNull String getConfigurationTypeId() {
    return HaxeHxcppProfilerConfigurationType.ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(@NotNull String name) {
    displayName = name;
  }
}
