package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.execution.Executor;
import com.intellij.execution.executors.RunExecutorSettings;
import com.intellij.profiler.DefaultProfilerExecutorGroup;
import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;

/** Lookup of the registered profiler configuration state for one of our configuration types. */
final class HaxeProfilerConfigurations {

  private HaxeProfilerConfigurations() {
  }

  /** The state registered on the profiler executor group, or the type's template when none is registered yet. */
  @NotNull
  static ProfilerConfigurationState stateFor(@NotNull String configurationTypeId) {
    DefaultProfilerExecutorGroup group = DefaultProfilerExecutorGroup.Companion.getInstance();
    if (group != null) {
      for (Executor child : group.childExecutors()) {
        RunExecutorSettings settings = group.getRegisteredSettings(child.getId());
        if (settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings
            && profilerSettings.getState().getConfigurationTypeId().equals(configurationTypeId)) {
          return profilerSettings.getState();
        }
      }
    }
    return templateFor(configurationTypeId);
  }

  private static ProfilerConfigurationState templateFor(String configurationTypeId) {
    return switch (configurationTypeId) {
      case HaxeHlProfilerConfigurationType.ID -> new HaxeHlProfilerConfigurationType().getTemplateState();
      case HaxeHxcppProfilerConfigurationType.ID -> new HaxeHxcppProfilerConfigurationType().getTemplateState();
      case HaxeHxcppTracyProfilerConfigurationType.ID -> new HaxeHxcppTracyProfilerConfigurationType().getTemplateState();
      default -> throw new IllegalArgumentException("unknown profiler configuration type " + configurationTypeId);
    };
  }
}
