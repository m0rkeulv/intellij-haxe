package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.progress.ProgressIndicator;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits for work that blocks in socket IO, and so cannot observe a progress
 * indicator itself, under a cancelable progress. The wait polls briefly, so
 * a cancel aborts the waiting task at once while the work runs on and
 * finishes on its own.
 */
@CustomLog
final class HaxeCancelableFutures {

  private static final long POLL_MS = 100;

  private HaxeCancelableFutures() {
  }

  /** The future's result, or null when it failed or the wait was interrupted; a cancel throws the platform's cancellation. */
  @Nullable
  static <T> T await(@NotNull Future<T> future, @NotNull ProgressIndicator indicator, @NotNull String failureMessage) {
    while (true) {
      indicator.checkCanceled();
      try {
        return future.get(POLL_MS, TimeUnit.MILLISECONDS);
      }
      catch (TimeoutException stillRunning) {
        // keep polling; the wait must stay short so cancellation is prompt
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
      catch (ExecutionException e) {
        log.warn(failureMessage, e.getCause());
        return null;
      }
    }
  }
}
