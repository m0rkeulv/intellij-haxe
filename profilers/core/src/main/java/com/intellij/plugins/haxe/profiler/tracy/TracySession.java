package com.intellij.plugins.haxe.profiler.tracy;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Everything decoded from one tracy connection, times in nanoseconds
 * relative to the session's first event. Zones are start-ordered. Plots are
 * keyed by their resolved name when a PlotName answer arrived (the live
 * query channel), else by a stable {@code plot@<pointer>} label.
 * {@code cpuUsage} carries the client's periodic system-load reports
 * (percent). Unmatched zone ends happen when the connection started
 * mid-zone; they are counted, not errors.
 */
public record TracySession(@NotNull TracyWelcome welcome,
                           @NotNull List<TracyZone> zones,
                           @NotNull List<Long> frameMarksNs,
                           @NotNull Map<String, List<PlotPoint>> plots,
                           @NotNull List<PlotPoint> cpuUsage,
                           @NotNull Map<Integer, String> threadNames,
                           long durationNs,
                           int unmatchedZoneEnds) {

  public record PlotPoint(long timeNs, double value) {
  }
}
