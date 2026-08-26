package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilerProcessUi;
import com.intellij.plugins.haxe.profiler.HaxeProfilerSnapshotOpener;
import com.intellij.plugins.haxe.profiler.HaxeTracyCapture;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneRecompressor;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneWriter;
import com.intellij.plugins.haxe.profiler.tracy.TracyLiveCapture;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The IU-side Tracy receiver: allocates the port the client will listen on
 * (handed to the process via TRACY_PORT), connects out with retries while
 * the process lives, captures the session and persists it as an HXTS v2
 * file. The exit notification offers to open it, or explains why nothing
 * was captured.
 */
public class HaxeIuTracyCapture implements HaxeTracyCapture {

  private static final Logger LOG = Logger.getInstance(HaxeIuTracyCapture.class);

  @Override
  public @Nullable Handle start(@NotNull Project project, @NotNull String displayName, @NotNull Path sessionFile) {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    catch (IOException e) {
      LOG.warn("could not allocate a tracy port", e);
      return null;
    }
    return new Capture(project, displayName, sessionFile, port, configuredCompressionLevel());
  }

  /** The final deflate level from the "hxcpp Tracy" profiler configuration; the default when none is registered. */
  private static int configuredCompressionLevel() {
    return HaxeProfilerConfigurations.stateFor(HaxeHxcppTracyProfilerConfigurationType.ID)
             instanceof HaxeHxcppTracyProfilerConfigurationState state
           ? state.getCompressionLevel()
           : HaxeHxcppTracyProfilerConfigurationState.DEFAULT_COMPRESSION_LEVEL;
  }

  private static final class Capture implements Handle {
    private final Project project;
    private final String displayName;
    private final Path sessionFile;
    private final int port;
    private final int finalLevel;
    private final AtomicBoolean exited = new AtomicBoolean();
    private final AtomicReference<TracyLiveCapture> live = new AtomicReference<>();
    private volatile HaxeProfilerProcessUi.Session session;

    Capture(Project project, String displayName, Path sessionFile, int port, int finalLevel) {
      this.project = project;
      this.displayName = displayName;
      this.sessionFile = HaxeCaptureFiles.perCaptureSessionPath(sessionFile);
      this.port = port;
      this.finalLevel = finalLevel;
      Thread receiver = new Thread(this::receive, "haxe-tracy-capture");
      receiver.setDaemon(true);
      // the profiled app runs concurrently - stay out of its scheduler slots
      receiver.setPriority(Thread.NORM_PRIORITY - 2);
      receiver.start();
    }

    @Override
    public int port() {
      return port;
    }

    @Override
    public void processExited() {
      exited.set(true);
      TracyLiveCapture capture = live.get();
      if (capture != null) {
        // the client waits for this acknowledgement before it lets go
        capture.requestDisconnect();
      }
    }

    private void receive() {
      TracyLiveCapture capture;
      try {
        capture = TracyLiveCapture.connect(port, () -> !exited.get());
      }
      catch (IOException e) {
        LOG.warn("tracy connect failed", e);
        notifyNothingCaptured();
        return;
      }
      if (capture == null) {
        notifyNothingCaptured();
        return;
      }
      live.set(capture);
      if (exited.get()) {
        capture.requestDisconnect(); // exit raced the connect
      }
      else {
        session = HaxeIuProfilerProcessUi.open(project, displayName, sessionFile,
                                               HaxeHxcppTracyProfilerConfigurationType.ID);
      }

      // zones spool to the session file AS THEY ARRIVE - a minutes-long
      // capture must never accumulate them on the heap. The live level
      // keeps the receiver's CPU out of the profiled app's way (a
      // configured level BELOW it wins - the user asked for even less);
      // the recompress pass runs after the app exited and the machine is
      // idle again.
      int liveLevel = Math.min(HxtZoneWriter.LIVE_LEVEL, finalLevel);
      long zoneCount;
      try {
        Files.createDirectories(sessionFile.getParent());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(sessionFile))) {
          HxtZoneWriter writer = new HxtZoneWriter(out, 0, liveLevel);
          TracySession session = capture.capture(writer);
          writer.finish(session);
          zoneCount = writer.zoneCount();
        }
      }
      catch (IOException | UncheckedIOException e) {
        LOG.warn("tracy capture failed", e);
        if (session != null) {
          session.failed(HaxeProfilerBundle.message("haxe.profiler.tracy.capture.failed", e.getMessage()));
        }
        else {
          notifyNothingCaptured();
        }
        return;
      }
      if (finalLevel > liveLevel) {
        try {
          HxtZoneRecompressor.recompress(sessionFile, finalLevel);
        }
        catch (IOException e) {
          // the live-level file is complete and valid - keep it
          LOG.warn("could not recompress the tracy session", e);
        }
      }
      if (session != null) {
        session.dataReady();
      }
      else {
        notifyCaptured(zoneCount);
      }
    }

    private void notifyCaptured(long zoneCount) {
      String content = HaxeProfilerBundle.message("haxe.profiler.tracy.captured",
                                                  sessionFile.toString(), zoneCount);
      Notification notification = group().createNotification(content, NotificationType.INFORMATION);
      HaxeProfilerSnapshotOpener opener = HaxeProfilerSnapshotOpener.getInstance();
      if (opener != null) {
        String openText = HaxeProfilerBundle.message("haxe.profiler.dump.open");
        notification.addAction(NotificationAction.createSimpleExpiring(openText, () -> opener.open(project, sessionFile)));
      }
      notification.notify(project);
    }

    private void notifyNothingCaptured() {
      String content = HaxeProfilerBundle.message("haxe.profiler.tracy.none");
      group().createNotification(content, NotificationType.WARNING).notify(project);
    }

    private static NotificationGroup group() {
      return NotificationGroupManager.getInstance().getNotificationGroup("haxe.profiler");
    }
  }
}
