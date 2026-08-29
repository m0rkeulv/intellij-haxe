package com.intellij.plugins.haxe.profiler.model;

import org.jetbrains.annotations.NotNull;

/**
 * One instant event on a capture's timeline — a user-emitted mark with a
 * label, the kind any profiler lane may produce (tracy messages today;
 * other runtimes' event APIs can feed the same lane). Times are
 * session-relative nanoseconds; {@code color} is RGB with 0 meaning
 * unspecified.
 */
public record TimelineEvent(int threadId, long timeNs, @NotNull String text, int color) {
}
