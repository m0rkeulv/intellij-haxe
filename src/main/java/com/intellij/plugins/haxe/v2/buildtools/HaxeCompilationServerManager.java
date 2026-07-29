package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.io.BaseOutputReader;
import com.intellij.util.net.NetUtils;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the project's haxe compilation server (`haxe --wait <port>`) alive: one
 * process per project, tied to the haxe binary it was started with (the server's
 * cache is per binary, so an SDK change forces a restart). Started lazily by the
 * first connected compile, killed with the project.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilationServerManager implements Disposable {

  /** Receives the server process output (and lifecycle lines) for the server console window. */
  public interface ServerOutputListener {
    void onOutput(@NotNull String text, @NotNull Key<?> outputType);
  }

  private record OutputChunk(@NotNull String text, @NotNull Key<?> outputType) {
  }

  private static final int BACKLOG_LIMIT = 2000;

  private final Project project;
  private final List<ServerOutputListener> outputListeners = new CopyOnWriteArrayList<>();
  private final Deque<OutputChunk> outputBacklog = new ArrayDeque<>();
  private OSProcessHandler processHandler;
  private int port = -1;
  private String startedExePath;

  public HaxeCompilationServerManager(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilationServerManager getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilationServerManager.class);
  }

  /**
   * Ensures the server runs for the haxe binary the given SDK resolves to and
   * returns its port, or -1 when the server is disabled or failed to start
   * (callers then compile without --connect).
   */
  public synchronized int ensureRunning(@Nullable String preferredSdkName) {
    HaxeBuildToolSettings settings = HaxeBuildToolSettings.getInstance(project);
    if (!settings.isCompilationServerEnabled()) {
      return -1;
    }

    String exePath = HaxeToolPathResolver.resolveHaxeExecutable(project, preferredSdkName);
    if (isAlive() && exePath.equals(startedExePath)) {
      return port;
    }

    stopLocked();
    int chosenPort = settings.getCompilationServerPort();
    try {
      if (chosenPort <= 0) {
        chosenPort = NetUtils.findAvailableSocketPort();
      }
      List<String> command = new ArrayList<>();
      command.add(exePath);
      command.addAll(ParametersListUtil.parse(settings.getCompilationServerArguments()));
      command.add("--wait");
      command.add(String.valueOf(chosenPort));

      // the process handler itself announces the command line as its first output
      GeneralCommandLine commandLine = new GeneralCommandLine(command)
        .withWorkDirectory(project.getBasePath());
      processHandler = new OSProcessHandler(commandLine) {
        // long-running, mostly idle daemon - the default reader busy-polls and wastes CPU
        @Override
        protected @NotNull BaseOutputReader.Options readerOptions() {
          return BaseOutputReader.Options.forMostlySilentProcess();
        }
      };
      processHandler.addProcessListener(new ProcessListener() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          broadcast(event.getText(), outputType);
        }

        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
          broadcast(HaxeBundle.message("haxe.compilation.server.terminated", event.getExitCode()) + "\n",
                    ProcessOutputType.SYSTEM);
          onServerTerminated(event.getExitCode());
        }
      });
      processHandler.startNotify();
      port = chosenPort;
      startedExePath = exePath;
      log.info("Started haxe compilation server on port " + chosenPort + " (" + exePath + ")");
      fireStateChanged();
      return port;
    }
    catch (ExecutionException | IOException e) {
      notifyStartFailed(StringUtil.notNullize(e.getMessage()));
      stopLocked();
      return -1;
    }
  }

  /** Registers a console sink and replays the buffered output so late-opened windows show history. */
  public synchronized void addOutputListener(@NotNull ServerOutputListener listener) {
    outputListeners.add(listener);
    outputBacklog.forEach(chunk -> listener.onOutput(chunk.text(), chunk.outputType()));
  }

  public void removeOutputListener(@NotNull ServerOutputListener listener) {
    outputListeners.remove(listener);
  }

  private synchronized void broadcast(@NotNull String text, @NotNull Key<?> outputType) {
    outputBacklog.addLast(new OutputChunk(text, outputType));
    while (outputBacklog.size() > BACKLOG_LIMIT) {
      outputBacklog.removeFirst();
    }
    for (ServerOutputListener listener : outputListeners) {
      listener.onOutput(text, outputType);
    }
  }

  public synchronized boolean isRunning() {
    return isAlive();
  }

  /** The running server's port, or -1. */
  public synchronized int getPort() {
    return isAlive() ? port : -1;
  }

  public synchronized void stop() {
    boolean wasRunning = isAlive();
    stopLocked();
    if (wasRunning) {
      fireStateChanged();
    }
  }

  private boolean isAlive() {
    return processHandler != null && !processHandler.isProcessTerminated();
  }

  private void stopLocked() {
    // clear state before destroying so the termination listener sees a deliberate stop
    OSProcessHandler handler = processHandler;
    processHandler = null;
    port = -1;
    startedExePath = null;
    if (handler != null && !handler.isProcessTerminated()) {
      handler.destroyProcess();
    }
  }

  private synchronized void onServerTerminated(int exitCode) {
    // deliberate stops null the handler first - anything else is the server dying on its own
    if (processHandler != null) {
      log.info("haxe compilation server terminated with exit code " + exitCode);
      processHandler = null;
      port = -1;
      startedExePath = null;
      fireStateChanged();
    }
  }

  /** Publishes on the EDT, outside the manager's lock, so listeners can freely query state or refresh UI. */
  private void fireStateChanged() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) {
        project.getMessageBus().syncPublisher(HaxeCompilationServerListener.TOPIC).serverStateChanged();
      }
    });
  }

  private void notifyStartFailed(@NotNull String detail) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("haxe.command")
      .createNotification(HaxeBundle.message("haxe.compilation.server.start.failed"), detail, NotificationType.WARNING)
      .notify(project);
  }

  @Override
  public void dispose() {
    stop();
  }
}
