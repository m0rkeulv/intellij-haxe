package com.intellij.plugins.haxe.profiler;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Bridges run configurations to the IU "Run with Profiler" executor without
 * them importing profiler classes. The only implementation comes from the
 * OPTIONAL profiler descriptor; on IDEs without the profiler module
 * {@link #getInstance()} is null and every launch is a plain one.
 */
public interface HaxeProfilerExecutorSupport {

  @Nullable
  static HaxeProfilerExecutorSupport getInstance() {
    return ApplicationManager.getApplication().getService(HaxeProfilerExecutorSupport.class);
  }

  /**
   * The sampling rate a profiler launch under this executor should use, or
   * null when the executor is not a HashLink profiler one (plain Run, Debug,
   * or another profiler configuration's executor).
   */
  @Nullable
  Integer hashlinkSamplesPerSecondFor(@NotNull Executor executor);

  /**
   * The compile additions an hxcpp profiling build must carry (profiler
   * defines plus the injected start/stop bootstrap writing {@code dumpPath}),
   * or null when the executor is not the hxcpp profiler one.
   * {@code limeFamily} picks the tool spelling — lime's {@code --haxeflag=}
   * wrappers against plain haxe flags.
   */
  @Nullable
  List<String> hxcppProfilingAdditionsFor(@NotNull Executor executor, boolean limeFamily, @NotNull Path dumpPath);

  /**
   * The profiler child executor that would launch this configuration (the
   * lane's registered profiler entry), or null without one — what a
   * tool-window "Profile" action executes with.
   */
  @Nullable
  Executor profilerExecutorFor(@NotNull RunConfiguration configuration);

  /** Null-safe lookup: null unless the profiler module is present AND the executor is a HashLink profiler one. */
  @Nullable
  static Integer hashlinkSamplesFor(@NotNull Executor executor) {
    HaxeProfilerExecutorSupport support = getInstance();
    return support == null ? null : support.hashlinkSamplesPerSecondFor(executor);
  }

  /** Null-safe form of {@link #hxcppProfilingAdditionsFor}. */
  @Nullable
  static List<String> hxcppProfilingAdditions(@NotNull Executor executor, boolean limeFamily, @NotNull Path dumpPath) {
    HaxeProfilerExecutorSupport support = getInstance();
    return support == null ? null : support.hxcppProfilingAdditionsFor(executor, limeFamily, dumpPath);
  }

  /** Null-safe form of {@link #profilerExecutorFor}. */
  @Nullable
  static Executor profilerExecutor(@NotNull RunConfiguration configuration) {
    HaxeProfilerExecutorSupport support = getInstance();
    return support == null ? null : support.profilerExecutorFor(configuration);
  }
}
