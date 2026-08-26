package com.intellij.plugins.haxe.profiler.model;

import org.jetbrains.annotations.NotNull;

/** One sampled thread; {@code id} is the target's native thread id. */
public record ProfilerThread(int id, @NotNull String name) {
}
