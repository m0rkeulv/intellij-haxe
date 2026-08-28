package com.intellij.plugins.haxe.profiler.bridge.flash;

import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/**
 * State of one "Flash Profiler" configuration: its user-visible name. The
 * data itself comes from the runtime's own telemetry channel, whose content
 * is governed by {@code ~/.telemetry.cfg} rather than per-profile options;
 * the sampler's ~1 ms cadence is the runtime's fixed rate, not tunable.
 */
public final class HaxeFlashProfilerConfigurationState implements ProfilerConfigurationState {

  private String displayName;

  public HaxeFlashProfilerConfigurationState(@NotNull String displayName) {
    this.displayName = displayName;
  }

  @Override
  public @NotNull String getConfigurationTypeId() {
    return HaxeFlashProfilerConfigurationType.ID;
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
