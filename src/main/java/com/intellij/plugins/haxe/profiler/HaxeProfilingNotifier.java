package com.intellij.plugins.haxe.profiler;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Watches a profiled run for its dump file. Every lane writes it only on an
 * orderly shutdown — a killed process, including the IDE Stop button,
 * produces nothing — so the exit notification either opens the snapshot or
 * shows the caller's lane-specific explanation of why there is none.
 */
public final class HaxeProfilingNotifier {

  private HaxeProfilingNotifier() {
  }

  /**
   * {@code missingMessageKey}: a HaxeProfilerBundle key taking the exit code
   * as {0} — each lane explains its own dump rules. With a {@code session}
   * the outcome lands in its profiler tab instead of a notification.
   */
  public static void watch(@NotNull Project project, @NotNull ProcessHandler handler,
                           @NotNull Path dumpPath, @NotNull String missingMessageKey,
                           @Nullable HaxeProfilerProcessUi.Session session) {
    long startedAt = System.currentTimeMillis();
    handler.addProcessListener(new ProcessListener() {
      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        notifyOutcome(project, dumpPath, startedAt, missingMessageKey, event.getExitCode(), session);
      }
    });
  }

  private static void notifyOutcome(Project project, Path dumpPath, long startedAt, String missingMessageKey,
                                    int exitCode, @Nullable HaxeProfilerProcessUi.Session session) {
    // a dump left behind by an EARLIER run must not read as this run's result
    if (writtenSince(dumpPath, startedAt)) {
      if (session != null) {
        session.dataReady();
      }
      else {
        notifySnapshotWritten(project, dumpPath);
      }
      return;
    }
    String content = HaxeProfilerBundle.message(missingMessageKey, exitCode);
    if (session != null) {
      session.failed(content);
    }
    else {
      group().createNotification(content, NotificationType.WARNING).notify(project);
    }
  }

  private static boolean writtenSince(Path dumpPath, long startedAt) {
    try {
      return Files.isRegularFile(dumpPath) && Files.getLastModifiedTime(dumpPath).toMillis() >= startedAt;
    }
    catch (IOException e) {
      return false;
    }
  }

  private static void notifySnapshotWritten(Project project, Path dumpPath) {
    String content = HaxeProfilerBundle.message("haxe.profiler.dump.written", dumpPath.toString());
    Notification notification = group().createNotification(content, NotificationType.INFORMATION);

    HaxeProfilerSnapshotOpener opener = HaxeProfilerSnapshotOpener.getInstance();
    if (opener != null) {
      String openText = HaxeProfilerBundle.message("haxe.profiler.dump.open");
      notification.addAction(NotificationAction.createSimpleExpiring(openText, () -> opener.open(project, dumpPath)));
    }
    else {
      // no IU profiler present - at least lead the user to the file
      notification.addAction(NotificationAction.createSimple(RevealFileAction.getActionName(),
                                                            () -> RevealFileAction.openFile(dumpPath.toFile())));
    }
    notification.notify(project);
  }

  @NotNull
  private static NotificationGroup group() {
    return NotificationGroupManager.getInstance().getNotificationGroup("haxe.profiler");
  }
}
