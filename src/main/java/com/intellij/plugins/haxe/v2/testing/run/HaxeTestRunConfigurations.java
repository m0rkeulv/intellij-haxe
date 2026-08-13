package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The single dispatch every unit-test entry point (tool window tree, container
 * context action; gutter markers in a later phase) converges on: find or create
 * the tests build file's run configuration, update its filter, and launch it
 * through the run-configuration machinery so it lands in the dropdown and can
 * be rerun from the main UI.
 */
public final class HaxeTestRunConfigurations {

  private HaxeTestRunConfigurations() {
  }

  /** Finds the build file's test configuration (matched by file path) or registers a new one; syncs filter and compile step. */
  @NotNull
  public static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                            @NotNull String buildFilePath,
                                                            @Nullable String filterPattern) {
    RunManager runManager = RunManager.getInstance(project);
    RunnerAndConfigurationSettings settings = runManager.getAllSettings().stream()
      .filter(candidate -> candidate.getConfiguration() instanceof HaxeTestRunConfiguration configuration
                           && configuration.getBuildFilePath().equals(buildFilePath))
      .findFirst()
      .orElse(null);

    boolean created = settings == null;
    if (created) {
      HaxeTestConfigurationFactory factory = HaxeTestRunConfigurationType.getInstance().getFactory();
      String name = HaxeBundle.message("haxe.test.config.suggested.name", PathUtil.getFileName(buildFilePath));
      settings = runManager.createConfiguration(name, factory);
    }
    HaxeTestRunConfiguration configuration = (HaxeTestRunConfiguration)settings.getConfiguration();
    configuration.setBuildFilePath(buildFilePath);
    configuration.setFilterPattern(filterPattern);
    configuration.syncCompileStep();
    if (created) {
      runManager.addConfiguration(settings);
    }
    return settings;
  }

  /** Runs the build file's tests under the Run executor, selecting the configuration in the dropdown. */
  public static void run(@NotNull Project project, @NotNull String buildFilePath, @Nullable String filterPattern) {
    launch(project, buildFilePath, filterPattern, DefaultRunExecutor.getRunExecutorInstance());
  }

  /** Runs the build file's tests under the Debug executor - only meaningful when {@link #isDebugSupported}. */
  public static void debug(@NotNull Project project, @NotNull String buildFilePath, @Nullable String filterPattern) {
    launch(project, buildFilePath, filterPattern, DefaultDebugExecutor.getDebugExecutorInstance());
  }

  private static void launch(@NotNull Project project,
                             @NotNull String buildFilePath,
                             @Nullable String filterPattern,
                             @NotNull Executor executor) {
    RunnerAndConfigurationSettings settings = findOrCreate(project, buildFilePath, filterPattern);
    RunManager.getInstance(project).setSelectedConfiguration(settings);
    ProgramRunnerUtil.executeConfiguration(settings, executor);
  }

  /** Whether the tests build's target has a debug lane (interp, HL, desktop C++ - see {@code HaxeTestDebugRunner}). */
  public static boolean isDebugSupported(@NotNull Project project, @NotNull String buildFilePath) {
    return HaxeTestLaunchPlanner.isDebuggableTarget(project, buildFilePath);
  }

  /**
   * Recomputes every test configuration's before-run compile step. Artifact
   * targets persist their compile arguments in that step, so a settings change
   * feeding into them (the live-reporter injection) must resync explicitly -
   * otherwise it only applies after the configuration is next edited.
   */
  public static void resyncCompileSteps(@NotNull Project project) {
    for (RunnerAndConfigurationSettings settings : RunManager.getInstance(project).getAllSettings()) {
      if (settings.getConfiguration() instanceof HaxeTestRunConfiguration configuration) {
        configuration.syncCompileStep();
      }
    }
  }
}
