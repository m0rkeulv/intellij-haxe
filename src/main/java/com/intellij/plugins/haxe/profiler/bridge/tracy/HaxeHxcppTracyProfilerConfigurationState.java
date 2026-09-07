package com.intellij.plugins.haxe.profiler.bridge.tracy;

import com.intellij.plugins.haxe.profiler.hxt.HxtZoneWriter;
import com.intellij.plugins.haxe.profiler.tracy.wire.TracyProtocolVersion;
import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * State of one "hxcpp Tracy" configuration: its user-visible name, the
 * session file's final deflate level (0-9; the live capture always writes
 * at the cheap level and the post-capture pass brings the file here),
 * whether the build instruments the GC's alloc/free hooks
 * ({@code HXCPP_TRACY_MEMORY} — the memory curves and GC lane, at some
 * runtime cost), whether the run starts the program ELEVATED so the
 * client's system tracing can stream the scheduler's context switches
 * (the Process CPU curve; Windows and Linux — tracy has no macOS
 * backend), and which Tracy protocol version the receiver offers the
 * client: none pinned = detect it (the client's broadcast, else the probe
 * ladder), a pinned one is offered alone.
 */
public final class HaxeHxcppTracyProfilerConfigurationState implements ProfilerConfigurationState {

  static final int DEFAULT_COMPRESSION_LEVEL = HxtZoneWriter.FINAL_LEVEL;

  private String displayName;
  private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;
  private boolean captureMemory = true;
  private boolean collectProcessCpu;
  private @Nullable TracyProtocolVersion pinnedProtocol;

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

  public boolean isCaptureMemory() {
    return captureMemory;
  }

  public void setCaptureMemory(boolean captureMemory) {
    this.captureMemory = captureMemory;
  }

  public boolean isCollectProcessCpu() {
    return collectProcessCpu;
  }

  public void setCollectProcessCpu(boolean collectProcessCpu) {
    this.collectProcessCpu = collectProcessCpu;
  }

  /** The protocol version offered alone, or null to detect the client's. */
  @Nullable
  public TracyProtocolVersion getPinnedProtocol() {
    return pinnedProtocol;
  }

  public void setPinnedProtocol(@Nullable TracyProtocolVersion pinnedProtocol) {
    this.pinnedProtocol = pinnedProtocol;
  }
}
