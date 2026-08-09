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
import com.intellij.openapi.projectRoots.Sdk;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the project's haxe compilation servers (`haxe --wait <port>`) alive.
 * The server's cache is per haxe BINARY, and per-module SDKs mean one project
 * can need several haxe versions at once — so instances are keyed by the
 * resolved executable path, one process each, started lazily by the first
 * connected compile against that SDK. A dead instance keeps its entry (and
 * output backlog) so console tabs survive restarts; everything dies with the
 * project.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilationServerManager implements Disposable {

  /** Receives one server's process output (and lifecycle lines) for the server console window. */
  public interface ServerOutputListener {
    void onOutput(@NotNull String text, @NotNull Key<?> outputType);
  }

  /** UI snapshot of one server instance; {@code id} is the haxe executable path the instance is keyed by. */
  public record ServerInfo(@NotNull String id, @NotNull String displayName, int port, boolean running) {
  }

  private record OutputChunk(@NotNull String text, @NotNull Key<?> outputType) {
  }

  private static final int BACKLOG_LIMIT = 2000;

  private static final class ServerInstance {
    final String exePath;
    final String displayName;
    @Nullable final String sdkName;
    final List<ServerOutputListener> listeners = new CopyOnWriteArrayList<>();
    final Deque<OutputChunk> backlog = new ArrayDeque<>();
    OSProcessHandler handler;
    int port = -1;

    ServerInstance(String exePath, String displayName, @Nullable String sdkName) {
      this.exePath = exePath;
      this.displayName = displayName;
      this.sdkName = sdkName;
    }

    boolean isAlive() {
      return handler != null && !handler.isProcessTerminated();
    }
  }

  private final Project project;
  private final Map<String, ServerInstance> servers = new LinkedHashMap<>();

  public HaxeCompilationServerManager(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilationServerManager getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilationServerManager.class);
  }

  /**
   * Ensures a server runs for the haxe binary the given SDK resolves to and
   * returns its port, or -1 when the server is disabled or failed to start
   * (callers then compile without --connect).
   */
  public synchronized int ensureRunning(@Nullable String preferredSdkName) {
    HaxeBuildToolSettings settings = HaxeBuildToolSettings.getInstance(project);
    if (!settings.isCompilationServerEnabled()) {
      return -1;
    }
    String exePath = HaxeToolPathResolver.resolveHaxeExecutable(project, preferredSdkName);
    ServerInstance instance = servers.get(exePath);
    if (instance == null) {
      instance = new ServerInstance(exePath, displayNameFor(preferredSdkName, exePath), preferredSdkName);
      servers.put(exePath, instance);
    }
    if (instance.isAlive()) {
      return instance.port;
    }
    return startLocked(instance);
  }

  /** The running server's port for the given SDK's binary, or -1. */
  public synchronized int getRunningPort(@Nullable String preferredSdkName) {
    String exePath = HaxeToolPathResolver.resolveHaxeExecutable(project, preferredSdkName);
    ServerInstance instance = servers.get(exePath);
    return instance != null && instance.isAlive() ? instance.port : -1;
  }

  public synchronized boolean isRunning() {
    return servers.values().stream().anyMatch(ServerInstance::isAlive);
  }

  /** Known instances (running or stopped-with-history), in start order. */
  @NotNull
  public synchronized List<ServerInfo> getServers() {
    List<ServerInfo> result = new ArrayList<>();
    for (ServerInstance instance : servers.values()) {
      boolean running = instance.isAlive();
      int port = running ? instance.port : -1;
      result.add(new ServerInfo(instance.exePath, instance.displayName, port, running));
    }
    return result;
  }

  /** Stops every instance (settings changes invalidate all of them). Entries and backlogs remain. */
  public synchronized void stop() {
    boolean anyStopped = false;
    for (ServerInstance instance : servers.values()) {
      anyStopped |= stopInstanceLocked(instance);
    }
    if (anyStopped) {
      fireStateChanged();
    }
  }

  public synchronized void stopServer(@NotNull String id) {
    ServerInstance instance = servers.get(id);
    if (instance != null && stopInstanceLocked(instance)) {
      clearServerDerivedState(id);
      fireStateChanged();
    }
  }

  /**
   * Stops the instance AND forgets it (backlog included) — closing its console
   * tab means this SDK's server is no longer wanted; the entry reappears when a
   * compile against that SDK next asks for it.
   */
  public synchronized void removeServer(@NotNull String id) {
    ServerInstance instance = servers.remove(id);
    if (instance != null) {
      stopInstanceLocked(instance);
      clearServerDerivedState(id);
      // fire even for a dead instance - the console mirrors the entry list
      fireStateChanged();
    }
  }

  /** (Re)starts the given instance's binary; also serves as plain start for a dead instance. Call off the EDT. */
  public synchronized void restartServer(@NotNull String id) {
    ServerInstance instance = servers.get(id);
    if (instance == null) {
      return;
    }
    stopInstanceLocked(instance);
    clearServerDerivedState(id);
    startLocked(instance);
  }

  /** Failures and request statistics describe the stopped process — a fresh server starts clean. */
  private void clearServerDerivedState(@NotNull String id) {
    HaxeContextHealth.getInstance(project).clearForServer(id);
    HaxeServerMetrics.getInstance(project).clear(id);
  }

  /** Registers a console sink for one instance and replays its buffered output. */
  public synchronized void addOutputListener(@NotNull String id, @NotNull ServerOutputListener listener) {
    ServerInstance instance = servers.get(id);
    if (instance == null) {
      return;
    }
    instance.listeners.add(listener);
    instance.backlog.forEach(chunk -> listener.onOutput(chunk.text(), chunk.outputType()));
  }

  public synchronized void removeOutputListener(@NotNull String id, @NotNull ServerOutputListener listener) {
    ServerInstance instance = servers.get(id);
    if (instance != null) {
      instance.listeners.remove(listener);
    }
  }

  private int startLocked(ServerInstance instance) {
    HaxeBuildToolSettings settings = HaxeBuildToolSettings.getInstance(project);
    try {
      int chosenPort = choosePortLocked(settings, instance);
      List<String> command = new ArrayList<>();
      command.add(instance.exePath);
      command.addAll(ParametersListUtil.parse(settings.getCompilationServerArguments()));
      command.add("--wait");
      command.add(String.valueOf(chosenPort));

      // the process handler itself announces the command line as its first output
      GeneralCommandLine commandLine = new GeneralCommandLine(command);
      // every request carries its own --cwd, so the server's working directory
      // is irrelevant - and a missing project directory (fixture projects,
      // freshly moved projects) must not fail the start
      String basePath = project.getBasePath();
      if (basePath != null && new File(basePath).isDirectory()) {
        commandLine.withWorkDirectory(basePath);
      }
      OSProcessHandler handler = new OSProcessHandler(commandLine) {
        // long-running, mostly idle daemon - the default reader busy-polls and wastes CPU
        @Override
        protected @NotNull BaseOutputReader.Options readerOptions() {
          return BaseOutputReader.Options.forMostlySilentProcess();
        }
      };
      handler.addProcessListener(new ProcessListener() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          broadcast(instance, event.getText(), outputType);
        }

        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
          broadcast(instance, HaxeBundle.message("haxe.compilation.server.terminated", event.getExitCode()) + "\n",
                    ProcessOutputType.SYSTEM);
          onServerTerminated(instance, handler, event.getExitCode());
        }
      });
      handler.startNotify();
      instance.handler = handler;
      instance.port = chosenPort;
      log.info("Started haxe compilation server on port " + chosenPort + " (" + instance.exePath + ")");
      fireStateChanged();
      return chosenPort;
    }
    catch (ExecutionException | IOException e) {
      notifyStartFailed(StringUtil.notNullize(e.getMessage()));
      stopInstanceLocked(instance);
      return -1;
    }
  }

  /** The configured fixed port serves the first instance; concurrent instances get auto-allocated ports. */
  private int choosePortLocked(HaxeBuildToolSettings settings, ServerInstance starting) throws IOException {
    int configured = settings.getCompilationServerPort();
    if (configured > 0) {
      boolean taken = servers.values().stream()
        .anyMatch(other -> other != starting && other.isAlive() && other.port == configured);
      if (!taken) {
        return configured;
      }
    }
    return NetUtils.findAvailableSocketPort();
  }

  private boolean stopInstanceLocked(ServerInstance instance) {
    // clear state before destroying so the termination listener sees a deliberate stop
    OSProcessHandler handler = instance.handler;
    boolean wasAlive = instance.isAlive();
    instance.handler = null;
    instance.port = -1;
    if (handler != null && !handler.isProcessTerminated()) {
      handler.destroyProcess();
    }
    return wasAlive;
  }

  private String displayNameFor(@Nullable String preferredSdkName, String exePath) {
    if (preferredSdkName != null) {
      return preferredSdkName;
    }
    Sdk configured = HaxeToolPathResolver.findConfiguredSdk(project);
    return configured != null ? configured.getName() : Path.of(exePath).getFileName().toString();
  }

  private synchronized void broadcast(ServerInstance instance, @NotNull String text, @NotNull Key<?> outputType) {
    instance.backlog.addLast(new OutputChunk(text, outputType));
    while (instance.backlog.size() > BACKLOG_LIMIT) {
      instance.backlog.removeFirst();
    }
    for (ServerOutputListener listener : instance.listeners) {
      listener.onOutput(text, outputType);
    }
  }

  private synchronized void onServerTerminated(ServerInstance instance, OSProcessHandler handler, int exitCode) {
    // deliberate stops null the handler first - anything else is the server dying on its own
    if (instance.handler == handler) {
      log.info("haxe compilation server terminated with exit code " + exitCode + " (" + instance.exePath + ")");
      instance.handler = null;
      instance.port = -1;
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
