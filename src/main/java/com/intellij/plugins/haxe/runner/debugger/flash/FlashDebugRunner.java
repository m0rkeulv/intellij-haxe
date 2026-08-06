package com.intellij.plugins.haxe.runner.debugger.flash;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.module.Module;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.HaxeFlashDebuggingUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Debug runner for the dedicated Flash configuration: attaches the Flex
 * debugger (from the Flash/Flex plugin, an optional dependency) to the
 * configured swf. Keys on {@link FlashRunConfiguration} only.
 *
 * The flex-touching code lives in {@link HaxeFlashDebuggingUtil}, which is
 * only classloaded AFTER the plugin-presence check - keep it that way, or a
 * flex-less IDE throws NoClassDefFoundError instead of the readable message.
 */
public class FlashDebugRunner extends GenericProgramRunner<RunnerSettings> {
  public static final String RUNNER_ID = "HaxeFlashDebugRunner";

  private static final PluginId FLEX_PLUGIN_ID = PluginId.getId("com.intellij.flex");

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof FlashRunConfiguration;
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull RunProfileState state, @NotNull ExecutionEnvironment environment)
    throws ExecutionException {
    FlashRunConfiguration configuration = (FlashRunConfiguration)environment.getRunProfile();
    Module module = configuration.requireModule();

    if (!PluginManagerCore.isLoaded(FLEX_PLUGIN_ID)) {
      throw new ExecutionException(HaxeBundle.message(
        PluginManagerCore.isDisabled(FLEX_PLUGIN_ID) ? "enable.flex.plugin" : "install.flex.plugin"));
    }
    if (configuration.getFlexSdkName().isBlank()) {
      throw new ExecutionException(HaxeDebuggerBundle.message("flash.runner.no.flex.sdk"));
    }

    String swfPath = configuration.resolveSwf().toString();
    return HaxeFlashDebuggingUtil.getDescriptor(module, environment, swfPath, configuration.getFlexSdkName());
  }
}
