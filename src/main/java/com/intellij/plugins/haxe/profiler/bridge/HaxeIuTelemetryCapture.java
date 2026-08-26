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
import com.intellij.plugins.haxe.profiler.HaxeTelemetryCapture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The IU-side telemetry receiver: one ephemeral loopback listener per
 * profiled run, spooling the collector's HXTS stream to the session file
 * verbatim (wire and disk share the format). The exit notification offers
 * to open the session in the profiler, or explains that nothing arrived.
 */
public class HaxeIuTelemetryCapture implements HaxeTelemetryCapture {

  private static final Logger LOG = Logger.getInstance(HaxeIuTelemetryCapture.class);
  /** A session smaller than its header carries no frame worth opening. */
  private static final int MINIMUM_SESSION_BYTES = 32;
  /** Waiting for a debuggee that never connects must not pin the listener forever. */
  private static final int ACCEPT_TIMEOUT_MS = 10 * 60 * 1000;
  private static final long DRAIN_TIMEOUT_SECONDS = 3;

  @Override
  public @Nullable Handle start(@NotNull Project project, @NotNull Path sessionFile) {
    try {
      return new Capture(project, sessionFile);
    }
    catch (IOException e) {
      LOG.warn("could not open a telemetry listener", e);
      return null;
    }
  }

  private static final class Capture implements Handle {
    private final Project project;
    private final Path sessionFile;
    private final ServerSocket listener;
    private final CountDownLatch drained = new CountDownLatch(1);
    private volatile long receivedBytes;

    Capture(Project project, Path sessionFile) throws IOException {
      this.project = project;
      this.sessionFile = sessionFile;
      listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      listener.setSoTimeout(ACCEPT_TIMEOUT_MS);
      Thread spooler = new Thread(this::spool, "haxe-telemetry-capture");
      spooler.setDaemon(true);
      spooler.start();
    }

    @Override
    public int port() {
      return listener.getLocalPort();
    }

    /** Accepts the single collector connection and spools its stream to the session file. */
    private void spool() {
      try (ServerSocket server = listener;
           Socket client = server.accept();
           InputStream in = client.getInputStream();
           OutputStream out = Files.newOutputStream(sessionFile)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
          out.write(buffer, 0, read);
          receivedBytes += read;
        }
      }
      catch (SocketTimeoutException neverConnected) {
        // the exit notification explains it
      }
      catch (IOException e) {
        // a closed listener after process exit is the normal no-connection path
        if (receivedBytes > 0) LOG.warn("telemetry stream ended abnormally", e);
      }
      finally {
        drained.countDown();
      }
    }

    @Override
    public void processExited() {
      try {
        // the collector's stop() flushes and closes right before Sys.exit -
        // give the last records a moment to arrive
        if (!drained.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          listener.close();
          drained.await(1, TimeUnit.SECONDS);
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      catch (IOException ignored) {
      }
      notifyOutcome();
    }

    private void notifyOutcome() {
      if (receivedBytes < MINIMUM_SESSION_BYTES) {
        String content = HaxeProfilerBundle.message("haxe.profiler.telemetry.none");
        group().createNotification(content, NotificationType.WARNING).notify(project);
        return;
      }
      String content = HaxeProfilerBundle.message("haxe.profiler.telemetry.captured",
                                                  sessionFile.toString(), receivedBytes / 1024);
      Notification notification = group().createNotification(content, NotificationType.INFORMATION);
      HaxeProfilerSnapshotOpener opener = HaxeProfilerSnapshotOpener.getInstance();
      if (opener != null) {
        String openText = HaxeProfilerBundle.message("haxe.profiler.dump.open");
        notification.addAction(NotificationAction.createSimpleExpiring(openText, () -> opener.open(project, sessionFile)));
      }
      notification.notify(project);
    }

    private static NotificationGroup group() {
      return NotificationGroupManager.getInstance().getNotificationGroup("haxe.profiler");
    }
  }
}
