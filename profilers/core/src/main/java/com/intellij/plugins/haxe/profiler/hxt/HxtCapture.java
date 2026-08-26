package com.intellij.plugins.haxe.profiler.hxt;

import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import org.jetbrains.annotations.NotNull;

/**
 * What an HXTS session file holds, by container version: v1 files carry a
 * sampled capture (the telemetry lane), v2 files an exact zone capture (the
 * tracy lane). One extension, one container, two data shapes.
 */
public sealed interface HxtCapture {

  record Samples(@NotNull ProfilerSnapshot snapshot) implements HxtCapture {
  }

  record Zones(@NotNull TracySession session) implements HxtCapture {
  }
}
