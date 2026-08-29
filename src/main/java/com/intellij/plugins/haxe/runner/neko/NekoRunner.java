package com.intellij.plugins.haxe.runner.neko;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.ExecutionUiService;
import com.intellij.execution.ui.RunContentDescriptor;
import org.jetbrains.annotations.NotNull;

/** Plain Run for the Neko configuration - the only executor it supports. */
public class NekoRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HaxeNekoRunner";

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultRunExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof NekoRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    // the state comes from NekoRunConfiguration.getState -> DapCommandLineRunningState
    ExecutionResult result = state.execute(environment.getExecutor(), this);
    return ExecutionUiService.getInstance().showRunContent(result, environment);
  }
}
