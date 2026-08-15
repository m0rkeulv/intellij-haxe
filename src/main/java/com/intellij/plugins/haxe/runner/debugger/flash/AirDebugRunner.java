package com.intellij.plugins.haxe.runner.debugger.flash;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.HaxeFlashDebuggingUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Debug runner for the dedicated AIR configuration: the Flex debugger (from
 * the Flash/Flex plugin, an optional dependency) waits while adl launches the
 * app, whose -debug swf connects back to it. Keys on
 * {@link AirRunConfiguration} only.
 *
 * The flex-touching code lives in {@link HaxeFlashDebuggingUtil}, which is
 * only classloaded AFTER the plugin-presence check - keep it that way, or a
 * flex-less IDE throws NoClassDefFoundError instead of the readable message.
 */
public class AirDebugRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HaxeAirDebugRunner";

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof AirRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    AirRunConfiguration configuration = (AirRunConfiguration)environment.getRunProfile();
    Module module = configuration.requireModule();

    FlexPluginGate.requireFlexPlugin();
    String flexSdkName = configuration.effectiveFlexSdkName();
    if (flexSdkName.isBlank()) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.no.flex.sdk"));
    }

    GeneralCommandLine adlCommandLine = configuration.createAdlCommandLine();
    return HaxeFlashDebuggingUtil.getAirDescriptor(module, environment, flexSdkName, adlCommandLine);
  }
}
