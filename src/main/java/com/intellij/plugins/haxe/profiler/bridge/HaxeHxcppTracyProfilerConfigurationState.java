package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.plugins.haxe.profiler.hxt.HxtZoneWriter;
import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/**
 * State of one "hxcpp Tracy" configuration: its user-visible name and the
 * session file's final deflate level (0-9; the live capture always writes
 * at the cheap level and the post-capture pass brings the file here).
 */
public final class HaxeHxcppTracyProfilerConfigurationState implements ProfilerConfigurationState {

  static final int DEFAULT_COMPRESSION_LEVEL = HxtZoneWriter.FINAL_LEVEL;

  private String displayName;
  private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;

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

  public int getCompressionLevel() {
    return compressionLevel;
  }

  public void setCompressionLevel(int level) {
    compressionLevel = Math.max(0, Math.min(level, 9));
  }
}
