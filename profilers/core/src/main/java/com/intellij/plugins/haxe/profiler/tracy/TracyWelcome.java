package com.intellij.plugins.haxe.profiler.tracy;

import org.jetbrains.annotations.NotNull;

/**
 * The client's packed welcome message, sent right after a successful
 * handshake. {@code timerMul} converts the client's raw timer ticks to
 * NANOSECONDS; {@code epoch} is the unix time the program started;
 * {@code onDemand} mirrors the client's TRACY_ON_DEMAND build flag.
 */
public record TracyWelcome(double timerMul, long initBegin, long initEnd, long delay, long resolution,
                           long epoch, long execTime, long pid, long samplingPeriod,
                           boolean onDemand, @NotNull String programName) {
}
