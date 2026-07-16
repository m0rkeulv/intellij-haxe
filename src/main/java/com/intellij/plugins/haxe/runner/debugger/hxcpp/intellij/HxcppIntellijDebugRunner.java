package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.HxcppDebugProcess;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.HxcppRunningState;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSessionStartedResult;
import java.io.IOException;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Debug runner for the HXCPP (IntelliJ debug server) configuration. Keys on
 * {@link HxcppIntellijRunConfiguration} only.
 *
 * Order matters: the backend binds its ephemeral loopback listener FIRST,
 * then the debuggee is spawned with that listener's address in the
 * HXCPP_DEBUG_HOST/PORT env vars — the executable's embedded debug server
 * connects out during startup and holds the program before {@code main} until
 * the IDE finishes configuring, so breakpoints are always installed before
 * user code runs. An ephemeral port per session means no port setting, no
 * collisions between concurrent sessions, and no leftover-instance poisoning.
 */
public class HxcppIntellijDebugRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HxcppIntellijDebugRunner";

  /** Generous: the debuggee connects during process startup, typically instantly. */
  private static final int DEBUGGEE_CONNECT_TIMEOUT_MILLIS = 30_000;

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof HxcppIntellijRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    HxcppIntellijRunConfiguration configuration = (HxcppIntellijRunConfiguration)environment.getRunProfile();

    // fail fast, before any UI is built
    configuration.requireModule();
    Path executable = configuration.resolveExecutable();
    Path workingDirectory = configuration.resolveWorkingDirectory();

    HxcppIntellijBackend backend;
    try {
      backend = new HxcppIntellijBackend(DEBUGGEE_CONNECT_TIMEOUT_MILLIS);
    } catch (IOException e) {
      throw new ExecutionException(HaxeBundle.message("hxcpp.intellij.runner.listen.failed", e.getMessage()));
    }

    ColoredProcessHandler debuggeeHandler;
    try {
      GeneralCommandLine commandLine =
        HxcppRunningState.createCommandLine(executable, workingDirectory, configuration.getProgramArguments())
          .withEnvironment(HxcppIntellijBackend.ENV_DEBUG_HOST, backend.getHost())
          .withEnvironment(HxcppIntellijBackend.ENV_DEBUG_PORT, Integer.toString(backend.getPort()));
      debuggeeHandler = new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
    } catch (ExecutionException | RuntimeException e) {
      closeQuietly(backend);
      throw e;
    }
    ProcessTerminatedListener.attach(debuggeeHandler, environment.getProject());

    try {
      // the session builder is the split-debugger-safe way to hand the
      // descriptor back to the execution manager (XDebugSession's own
      // getRunContentDescriptor is deprecated and logs an error)
      XSessionStartedResult started = XDebuggerManager.getInstance(environment.getProject())
        .newSessionBuilder(new XDebugProcessStarter() {
          @NotNull
          @Override
          public XDebugProcess start(@NotNull XDebugSession session) {
            // lightweight: the DAP conversation starts asynchronously in sessionInitialized()
            return new HxcppDebugProcess(session, backend, debuggeeHandler);
          }
        })
        .environment(environment)
        .startSession();
      return started.getRunContentDescriptor();
    } catch (ExecutionException | RuntimeException e) {
      debuggeeHandler.destroyProcess();
      closeQuietly(backend);
      throw e;
    }
  }

  private static void closeQuietly(HxcppIntellijBackend backend) {
    try {
      backend.close();
    } catch (IOException ignored) {
      // teardown on a failed start; the original failure matters more
    }
  }
}
