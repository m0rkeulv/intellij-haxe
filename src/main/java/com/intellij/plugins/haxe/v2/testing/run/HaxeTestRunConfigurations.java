package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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

  /** Finds the build file's whole-run test configuration (matched by file path) or registers a new one; syncs filter and compile step. */
  @NotNull
  public static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                            @NotNull String buildFilePath,
                                                            @Nullable String filterPattern) {
    return findOrCreate(project, buildFilePath, filterPattern, null, null);
  }

  @NotNull
  private static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                             @NotNull String buildFilePath,
                                                             @Nullable String filterPattern,
                                                             @Nullable String testClass,
                                                             @Nullable String testMethod) {
    RunManager runManager = RunManager.getInstance(project);
    String wantedClass = StringUtil.notNullize(testClass);
    String wantedMethod = StringUtil.notNullize(testMethod);
    RunnerAndConfigurationSettings settings = runManager.getAllSettings().stream()
      .filter(candidate -> candidate.getConfiguration() instanceof HaxeTestRunConfiguration configuration
                           && configuration.getBuildFilePath().equals(buildFilePath)
                           && configuration.getTestClass().equals(wantedClass)
                           && configuration.getTestMethod().equals(wantedMethod))
      .findFirst()
      .orElse(null);

    boolean created = settings == null;
    if (created) {
      HaxeTestConfigurationFactory factory = HaxeTestRunConfigurationType.getInstance().getFactory();
      settings = runManager.createConfiguration("haxe-tests", factory);
    }
    HaxeTestRunConfiguration configuration = (HaxeTestRunConfiguration)settings.getConfiguration();
    configuration.setBuildFilePath(buildFilePath);
    configuration.setFilterPattern(filterPattern);
    configuration.setSingleRun(testClass, testMethod);
    if (created) {
      settings.setName(StringUtil.notNullize(configuration.suggestedName(), settings.getName()));
    }
    configuration.syncCompileStep();
    if (created) {
      runManager.addConfiguration(settings);
    }
    return settings;
  }

  /** Runs the build file's tests under the Run executor, selecting the configuration in the dropdown. */
  public static void run(@NotNull Project project, @NotNull String buildFilePath, @Nullable String filterPattern) {
    launch(project, buildFilePath, filterPattern, null, null, DefaultRunExecutor.getRunExecutorInstance());
  }

  /** Runs the build file's tests under the Debug executor - only meaningful when {@link #isDebugSupported}. */
  public static void debug(@NotNull Project project, @NotNull String buildFilePath, @Nullable String filterPattern) {
    launch(project, buildFilePath, filterPattern, null, null, DefaultDebugExecutor.getDebugExecutorInstance());
  }

  /** Runs one suite class (or one of its tests, when {@code testMethod} is set) from a gutter marker. */
  public static void runSingle(@NotNull Project project,
                               @NotNull String buildFilePath,
                               @NotNull String testClass,
                               @Nullable String testMethod) {
    launch(project, buildFilePath, null, testClass, testMethod, DefaultRunExecutor.getRunExecutorInstance());
  }

  /** Debugs one suite class (or one of its tests) from a gutter marker - only meaningful when {@link #isDebugSupported}. */
  public static void debugSingle(@NotNull Project project,
                                 @NotNull String buildFilePath,
                                 @NotNull String testClass,
                                 @Nullable String testMethod) {
    launch(project, buildFilePath, null, testClass, testMethod, DefaultDebugExecutor.getDebugExecutorInstance());
  }

  private static void launch(@NotNull Project project,
                             @NotNull String buildFilePath,
                             @Nullable String filterPattern,
                             @Nullable String testClass,
                             @Nullable String testMethod,
                             @NotNull Executor executor) {
    RunnerAndConfigurationSettings settings = findOrCreate(project, buildFilePath, filterPattern, testClass, testMethod);
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
   * The computation parses build files (PSI/VFS), so it runs in the
   * background; only the configuration mutation lands on the EDT.
   */
  public static void resyncCompileSteps(@NotNull Project project) {
    ReadAction.nonBlocking(() -> {
        List<Runnable> applications = new ArrayList<>();
        for (RunnerAndConfigurationSettings settings : RunManager.getInstance(project).getAllSettings()) {
          if (settings.getConfiguration() instanceof HaxeTestRunConfiguration configuration) {
            List<BeforeRunTask<?>> tasks = configuration.computedCompileStep();
            applications.add(() -> configuration.setBeforeRunTasks(tasks));
          }
        }
        return applications;
      })
      .expireWith(project)
      .finishOnUiThread(ModalityState.defaultModalityState(), applications -> applications.forEach(Runnable::run))
      .submit(AppExecutorUtil.getAppExecutorService());
  }
}
