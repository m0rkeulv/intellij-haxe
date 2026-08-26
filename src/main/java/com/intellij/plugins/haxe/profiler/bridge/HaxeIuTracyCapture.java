package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilerSnapshotOpener;
import com.intellij.plugins.haxe.profiler.HaxeTracyCapture;
import com.intellij.plugins.haxe.profiler.hxt.HxtZoneCodec;
import com.intellij.plugins.haxe.profiler.tracy.TracyLiveCapture;
import com.intellij.plugins.haxe.profiler.tracy.TracySession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
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
  public @Nullable Handle start(@NotNull Project project, @NotNull Path sessionFile) {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    catch (IOException e) {
      LOG.warn("could not allocate a tracy port", e);
      return null;
    }
    return new Capture(project, sessionFile, port);
  }

  private static final class Capture implements Handle {
    private final Project project;
    private final Path sessionFile;
    private final int port;
    private final AtomicBoolean exited = new AtomicBoolean();
    private final AtomicReference<TracyLiveCapture> live = new AtomicReference<>();

    Capture(Project project, Path sessionFile, int port) {
      this.project = project;
      this.sessionFile = sessionFile;
      this.port = port;
      Thread receiver = new Thread(this::receive, "haxe-tracy-capture");
      receiver.setDaemon(true);
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
      TracySession session;
      try {
        TracyLiveCapture capture = TracyLiveCapture.connect(port, () -> !exited.get());
        if (capture == null) {
          notifyNothingCaptured();
          return;
        }
        live.set(capture);
        if (exited.get()) capture.requestDisconnect(); // exit raced the connect
        session = capture.capture();
      }
      catch (IOException e) {
        LOG.warn("tracy capture failed", e);
        notifyNothingCaptured();
        return;
      }

      try (OutputStream out = Files.newOutputStream(sessionFile)) {
        HxtZoneCodec.write(session, out);
      }
      catch (IOException e) {
        LOG.warn("could not persist the tracy session", e);
        return;
      }
      notifyCaptured(session);
    }

    private void notifyCaptured(TracySession session) {
      String content = HaxeProfilerBundle.message("haxe.profiler.tracy.captured",
                                                  sessionFile.toString(), session.zones().size());
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
