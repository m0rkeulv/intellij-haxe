package com.intellij.plugins.haxe.profiler;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Receives a profiled process's telemetry stream without run configurations
 * importing profiler classes: the capture listens on an ephemeral loopback
 * port, the launch hands the endpoint to the injected collector through
 * {@link #ENDPOINT_ENV_VAR}, and the received session is persisted to the
 * given file when the process ends. The only implementation comes from the
 * OPTIONAL profiler descriptor.
 */
public interface HaxeTelemetryCapture {

  /** The env var the injected collector reads: {@code host:port} to stream to. */
  String ENDPOINT_ENV_VAR = "IJ_HAXE_TELEMETRY";

  @Nullable
  static HaxeTelemetryCapture getInstance() {
    return ApplicationManager.getApplication().getService(HaxeTelemetryCapture.class);
  }

  /** One run's capture, or null when the listener cannot open. */
  @Nullable
  Handle start(@NotNull Project project, @NotNull Path sessionFile);

  /** Null-safe form of {@link #start}: null without the profiler module too. */
  @Nullable
  static Handle startCapture(@NotNull Project project, @NotNull Path sessionFile) {
    HaxeTelemetryCapture capture = getInstance();
    return capture == null ? null : capture.start(project, sessionFile);
  }

  interface Handle {
    int port();

    /**
     * Called after the profiled process terminated: waits briefly for the
     * stream to drain, persists the session and notifies — offering to open
     * it, or explaining that nothing arrived.
     */
    void processExited();
  }
}
