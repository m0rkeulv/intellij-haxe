package com.intellij.plugins.haxe.runner.debugger.interp;

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
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSessionStartedResult;
import java.io.IOException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Debug runner for the Haxe interpreter configuration (experimental). Keys on
 * {@link InterpRunConfiguration} only.
 *
 * Order matters: the in-process adapter backend binds its listener FIRST (its
 * constructor), then haxe is spawned with {@code -D eval-debugger} pointing at
 * it — the eval VM connects out during compiler startup and holds execution
 * (the interpreted main, or the build's first macro) until the adapter
 * continues it on configurationDone, so breakpoints are always installed
 * before any user code runs.
 */
public class InterpDebugRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HaxeInterpDebugRunner";

  /** Generous: the eval VM connects during compiler startup, typically instantly. */
  private static final long VM_CONNECT_TIMEOUT_MILLIS = 30_000;

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof InterpRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    InterpRunConfiguration configuration = (InterpRunConfiguration)environment.getRunProfile();

    // fail fast, before any UI is built
    configuration.requireModule();

    InterpDapBackend backend;
    try {
      backend = new InterpDapBackend(VM_CONNECT_TIMEOUT_MILLIS);
    } catch (IOException e) {
      throw new ExecutionException(HaxeBundle.message("interp.runner.listener.failed", e.getMessage()));
    }

    ColoredProcessHandler haxeHandler;
    try {
      GeneralCommandLine commandLine = configuration.createCommandLine(
        List.of("-D", "eval-debugger=127.0.0.1:" + backend.getVmPort()));
      haxeHandler = new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
    } catch (ExecutionException | RuntimeException e) {
      closeQuietly(backend);
      throw e;
    }
    ProcessTerminatedListener.attach(haxeHandler, environment.getProject());

    try {
      XSessionStartedResult started = XDebuggerManager.getInstance(environment.getProject())
        .newSessionBuilder(new XDebugProcessStarter() {
          @NotNull
          @Override
          public XDebugProcess start(@NotNull XDebugSession session) {
            // lightweight: the DAP conversation starts asynchronously in sessionInitialized()
            return new HxcppDebugProcess(session, backend, haxeHandler);
          }
        })
        .environment(environment)
        .startSession();
      return started.getRunContentDescriptor();
    } catch (ExecutionException | RuntimeException e) {
      haxeHandler.destroyProcess();
      closeQuietly(backend);
      throw e;
    }
  }

  private static void closeQuietly(InterpDapBackend backend) {
    try {
      backend.close();
    } catch (IOException ignored) {
      // teardown on a failed start; the original failure matters more
    }
  }
}
