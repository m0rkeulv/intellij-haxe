package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.execution.Executor;
import com.intellij.execution.executors.RunExecutorSettings;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.plugins.haxe.profiler.bridge.flash.HaxeFlashProfilerConfigurationType;
import com.intellij.plugins.haxe.profiler.bridge.hashlink.HaxeHlProfilerConfigurationType;
import com.intellij.plugins.haxe.profiler.bridge.hxcpp.HaxeHxcppProfilerConfigurationType;
import com.intellij.plugins.haxe.profiler.bridge.js.HaxeJsProfilerConfigurationType;
import com.intellij.plugins.haxe.profiler.bridge.tracy.HaxeHxcppTracyProfilerConfigurationType;
import com.intellij.profiler.DefaultProfilerExecutorGroup;
import com.intellij.profiler.api.configurations.ProfilerConfigurationState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Lookup of registered profiler configuration states. Each named profiler
 * configuration is one child executor of the Run-with-Profiler group, so
 * behavior follows the LAUNCHING executor's state; the by-type form is the
 * fallback for contexts without one (display, no-executor defaults).
 */
public final class HaxeProfilerConfigurations {

  private HaxeProfilerConfigurations() {
  }

  /** The state the launching profiler executor carries, or null when the executor is not a profiler entry. */
  @Nullable
  public static ProfilerConfigurationState stateFor(@NotNull Executor executor) {
    return stateForExecutor(executor.getId());
  }

  /** The id-keyed form of {@link #stateFor(Executor)} for callers holding only the executor id. */
  @Nullable
  public static ProfilerConfigurationState stateForExecutor(@NotNull String executorId) {
    DefaultProfilerExecutorGroup group = DefaultProfilerExecutorGroup.Companion.getInstance();
    RunExecutorSettings settings = group == null ? null : group.getRegisteredSettings(executorId);
    return settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings
           ? profilerSettings.getState()
           : null;
  }

  /** Which profiler configuration types serve each lane — the ONE home of that mapping; the switch is exhaustive, so a new lane fails compilation here instead of NPE-ing at runtime. */
  @NotNull
  public static Set<String> typeIdsFor(HaxeProfilableRunConfiguration.@NotNull Lane lane) {
    return switch (lane) {
      case HASHLINK -> Set.of(HaxeHlProfilerConfigurationType.ID);
      case HXCPP -> Set.of(HaxeHxcppProfilerConfigurationType.ID, HaxeHxcppTracyProfilerConfigurationType.ID);
      case FLASH -> Set.of(HaxeFlashProfilerConfigurationType.ID);
      case JS -> Set.of(HaxeJsProfilerConfigurationType.ID);
    };
  }

  /** The type's FIRST registered state, or its template when none is registered yet. */
  @NotNull
  public static ProfilerConfigurationState stateFor(@NotNull String configurationTypeId) {
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
