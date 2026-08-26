package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/** State of one "hxcpp Tracy" configuration: only its user-visible name — the instrumentation has no tunables yet. */
public final class HaxeHxcppTracyProfilerConfigurationState implements ProfilerConfigurationState {

  private String displayName;

  public HaxeHxcppTracyProfilerConfigurationState(@NotNull String displayName) {
    this.displayName = displayName;
  }

  @Override
  public @NotNull String getConfigurationTypeId() {
    return HaxeHxcppTracyProfilerConfigurationType.ID;
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
