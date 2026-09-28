package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Waits for a server request that runs on a pooled thread, from a caller
 * that holds the read lock and so must not touch the network itself. The
 * wait checks for cancellation, so a pending write action (typing) or a
 * cancelled progress abandons it; the request still completes on its own.
 */
@CustomLog
final class HaxePooledAnswers {

  private HaxePooledAnswers() {
  }

  /** The request's answer, or null when it failed or gave none within {@code timeoutMs}; {@code what} labels the log line. */
  @Nullable
  static <T> T await(@NotNull Future<T> request, long timeoutMs, @NotNull String what) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    ProgressIndicatorUtils.awaitWithCheckCanceled(() -> request.isDone() || System.currentTimeMillis() >= deadline);
    if (!request.isDone()) {
      log.info(what + " gave no answer within " + timeoutMs + " ms");
      return null;
    }
    try {
      return request.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      log.warn(what + " failed: " + e.getCause());
      return null;
    }
  }
}
