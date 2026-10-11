package com.intellij.plugins.haxe.v2.buildtools.libraries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs an asynchronous action one run at a time. Requests arriving while a
 * run is in progress fold into ONE follow-up run, started when the current
 * run signals completion - a burst of triggers costs one extra run. A
 * request's callback runs after a run that started after the request, so
 * it sees the state the request was made in.
 */
final class HaxeCoalescingRunner {

  /** Starts one run; the run calls the handed Runnable exactly once when it completed. */
  private final Consumer<Runnable> run;

  // guarded by this: a run in progress, and the callbacks of the requests
  // that arrived during it (non-null = a follow-up run is wanted)
  private boolean running;
  private List<Runnable> pending;

  HaxeCoalescingRunner(@NotNull Consumer<Runnable> run) {
    this.run = run;
  }

  void request(@Nullable Runnable onFinished) {
    List<Runnable> callbacks;
    synchronized (this) {
      if (running) {
        if (pending == null) pending = new ArrayList<>();
        if (onFinished != null) pending.add(onFinished);
        return;
      }
      running = true;
      callbacks = onFinished == null ? List.of() : List.of(onFinished);
    }
    start(callbacks);
  }

  private void start(@NotNull List<Runnable> callbacks) {
    run.accept(() -> finish(callbacks));
  }

  private void finish(@NotNull List<Runnable> callbacks) {
    callbacks.forEach(Runnable::run);
    List<Runnable> followUp;
    synchronized (this) {
      followUp = pending;
      pending = null;
      running = followUp != null;
    }
    if (followUp != null) start(followUp);
  }
}
