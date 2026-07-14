package com.intellij.plugins.haxe.hxcpp;

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
import com.intellij.openapi.module.Module;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.hxcpp.adapter.HxcppDebugAdapter;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import java.io.IOException;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Debug runner for the dedicated HXCPP configuration (experimental). Keys on
 * {@link HxcppRunConfiguration} only, so the legacy Flash/hxcpp debugger is
 * never involved.
 *
 * Order matters: the in-process {@link HxcppDebugAdapter} binds its listener
 * FIRST (its constructor), then the debuggee is spawned — the executable's
 * embedded debug server connects out to that listener during startup and
 * holds the program before {@code main} until the adapter continues it, so
 * breakpoints are always installed before user code runs.
 */
public class HxcppDebugRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HxcppDebugRunner";

  /** Generous: the debuggee connects during process startup, typically instantly. */
  private static final long DEBUGGEE_CONNECT_TIMEOUT_MILLIS = 30_000;

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof HxcppRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    HxcppRunConfiguration configuration = (HxcppRunConfiguration)environment.getRunProfile();
    Module module = configuration.requireModule();

    // fail fast, before any UI is built
    Path executable = configuration.resolveExecutable();
    Path workingDirectory = configuration.resolveWorkingDirectory();
    String debugHost = configuration.getDebugHost();
    int debugPort = configuration.resolveDebugPort();

    // one debug session per port: the port is baked into the executable at
    // compile time, so a second concurrent session cannot get its own
    HxcppDebugAdapter adapter;
    try {
      adapter = new HxcppDebugAdapter(debugHost, debugPort, DEBUGGEE_CONNECT_TIMEOUT_MILLIS);
    } catch (IOException e) {
      throw new ExecutionException(
        HaxeBundle.message("hxcpp.runner.port.busy", debugHost, debugPort, e.getMessage()));
    }

    ColoredProcessHandler debuggeeHandler;
    try {
      GeneralCommandLine commandLine =
        HxcppRunningState.createCommandLine(executable, workingDirectory, configuration.getProgramArguments());
      debuggeeHandler = new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
    } catch (ExecutionException | RuntimeException e) {
      closeQuietly(adapter);
      throw e;
    }
    ProcessTerminatedListener.attach(debuggeeHandler, environment.getProject());

    try {
      XDebugSession debugSession = XDebuggerManager.getInstance(environment.getProject()).startSession(
        environment,
        new XDebugProcessStarter() {
          @NotNull
          @Override
          public XDebugProcess start(@NotNull XDebugSession session) {
            // lightweight: the DAP conversation starts asynchronously in sessionInitialized()
            return new HxcppDebugProcess(session, module, adapter, debuggeeHandler);
          }
        });
      return debugSession.getRunContentDescriptor();
    } catch (ExecutionException | RuntimeException e) {
      debuggeeHandler.destroyProcess();
      closeQuietly(adapter);
      throw e;
    }
  }

  private static void closeQuietly(HxcppDebugAdapter adapter) {
    try {
      adapter.close();
    } catch (IOException ignored) {
      // teardown on a failed start; the original failure matters more
    }
  }
}
