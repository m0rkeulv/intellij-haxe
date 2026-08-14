package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapBackend;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapDebugRunnerBase;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HashLinkBackend;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HashLinkDebugRunner;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HlExecutableResolver;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij.HxcppIntellijBackend;
import com.intellij.plugins.haxe.runner.debugger.interp.InterpDapBackend;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildClasspaths;
import com.intellij.plugins.haxe.v2.testing.run.HaxeTestLaunchPlanner.Plan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Debug executor for Haxe unit-test configurations, dispatching on the launch
 * plan's target. An HL artifact debugs exactly like a HashLink session
 * ({@code hl --debug <port> --debug-wait}, bundled adapter attached by pid); a
 * single-stage interp build debugs through the eval lane (the compile IS the
 * debuggee: haxe spawned with {@code -D eval-debugger} pointing at the
 * in-process adapter, which holds execution until breakpoints are installed);
 * a desktop C++ binary debugs through its embedded intellij-hxcpp-debug-server
 * (compiled in by the before-run step's debug additions, connecting out via
 * the HXCPP_DEBUG_HOST/PORT env vars). Either way {@code DapDebugProcess}
 * makes the session console an SM test console (see {@code DapTestConsoles})
 * — the TeamCity messages on the debuggee's stdout drive the test tree while
 * breakpoints work. For artifact targets, the before-run compile step attached
 * by {@code HaxeTestRunConfiguration.syncCompileStep()} builds with the
 * framework defines (plus the debug additions) before the session starts.
 */
// TODO: js/flash test debugging — both route program output through the debug
//  connection instead of process stdout (browser CDP console, flash fdb), so they
//  need DAP/debugger output events replayed into the process handler before the
//  SM console can see the TeamCity stream (see doc/test-runner-phase1-notes.md).
public class HaxeTestDebugRunner extends DapDebugRunnerBase<HaxeTestRunConfiguration, DapBackend> {
  public static final String RUNNER_ID = "HaxeTestDebugRunner";

  /** Generous: the eval VM connects during compiler startup, typically instantly. */
  private static final long VM_CONNECT_TIMEOUT_MILLIS = 30_000;

  /** Generous: the hxcpp debuggee's embedded server connects during process startup, typically instantly. */
  private static final int DEBUGGEE_CONNECT_TIMEOUT_MILLIS = 30_000;

  @NotNull
  @Override
  public String getRunnerId() {
    return RUNNER_ID;
  }

  @Override
  protected Class<HaxeTestRunConfiguration> configurationClass() {
    return HaxeTestRunConfiguration.class;
  }

  @Override
  protected void validate(HaxeTestRunConfiguration configuration) throws ExecutionException {
    Plan plan = debuggablePlan(configuration);
    boolean needsSdkRuntime = plan.target() == HaxeTarget.HL && HaxeTestLaunchPlanner.packagedHlBoot(plan) == null;
    if (needsSdkRuntime) {
      resolveHlExecutable(configuration);
    }
  }

  @Override
  protected DapBackend createBackend(HaxeTestRunConfiguration configuration) throws ExecutionException {
    Plan plan = debuggablePlan(configuration);
    try {
      return switch (plan.target()) {
        case HL -> new HashLinkBackend(hlRuntime(configuration, plan),
                                       hlProgram(plan),
                                       HashLinkDebugRunner.findFreePort(),
                                       sourceDirectories(configuration));
        case CPP -> new HxcppIntellijBackend(DEBUGGEE_CONNECT_TIMEOUT_MILLIS, sourceDirectories(configuration));
        default -> new InterpDapBackend(VM_CONNECT_TIMEOUT_MILLIS);
      };
    } catch (IOException e) {
      throw new ExecutionException(HaxeDebuggerBundle.message("interp.runner.listener.failed", e.getMessage()));
    }
  }

  @Override
  protected GeneralCommandLine createCommandLine(HaxeTestRunConfiguration configuration, DapBackend backend)
    throws ExecutionException {
    Plan plan = debuggablePlan(configuration);
    if (backend instanceof HashLinkBackend hashLink) {
      return new GeneralCommandLine()
        .withExePath(hlRuntime(configuration, plan).toString())
        .withParameters("--debug", Integer.toString(hashLink.getDebugPort()),
                        "--debug-wait", hlProgram(plan).toString())
        .withWorkDirectory(plan.workDirectory());
    }
    if (backend instanceof HxcppIntellijBackend hxcpp) {
      // the binary's embedded debug server (compiled in by the debug
      // additions) connects out to the backend's listener during startup
      return new GeneralCommandLine(plan.command())
        .withWorkDirectory(plan.workDirectory())
        .withEnvironment(HxcppIntellijBackend.ENV_DEBUG_HOST, hxcpp.getHost())
        .withEnvironment(HxcppIntellijBackend.ENV_DEBUG_PORT, Integer.toString(hxcpp.getPort()));
    }
    // interp: the plan's compile command (framework defines included) IS the
    // debuggee - the eval VM inside it connects out to the adapter's port
    InterpDapBackend interp = (InterpDapBackend)backend;
    List<String> command = new ArrayList<>(plan.command());
    command.add("-D");
    command.add("eval-debugger=127.0.0.1:" + interp.getVmPort());
    return new GeneralCommandLine(command).withWorkDirectory(plan.workDirectory());
  }

  /**
   * The launch plan, required to be a debuggable shape: an HL artifact or lime
   * HL package, a desktop C++ binary, or a single-stage interp build.
   */
  @NotNull
  private static Plan debuggablePlan(HaxeTestRunConfiguration configuration) throws ExecutionException {
    Plan plan = ReadAction.computeBlocking(() -> HaxeTestLaunchPlanner.planForDebug(
      configuration.getProject(), configuration.getBuildFilePath(), configuration.getFilterPattern()));
    boolean debuggable = plan.singleStage()
      || plan.target() == HaxeTarget.CPP
      || HaxeTestLaunchPlanner.hlArtifact(plan) != null
      || HaxeTestLaunchPlanner.packagedHlBoot(plan) != null;
    if (!debuggable) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.debug.unsupported.target"));
    }
    return plan;
  }

  /**
   * The runtime that executes the HL debuggee AND the bundled adapter: the
   * SDK-resolved hl for a plain artifact; for a lime package the bundled
   * runtime beside the bytecode - it carries the app's .hdll libraries, which
   * the SDK's plain hl lacks.
   */
  @NotNull
  private static Path hlRuntime(HaxeTestRunConfiguration configuration, @NotNull Plan plan) throws ExecutionException {
    return HaxeTestLaunchPlanner.packagedHlBoot(plan) != null
           ? Path.of(plan.command().get(0))
           : resolveHlExecutable(configuration);
  }

  /** The HL bytecode to debug: the plain artifact, or the lime package's hlboot.dat. */
  @NotNull
  private static Path hlProgram(@NotNull Plan plan) throws ExecutionException {
    Path artifact = HaxeTestLaunchPlanner.hlArtifact(plan);
    if (artifact != null) return artifact;
    Path boot = HaxeTestLaunchPlanner.packagedHlBoot(plan);
    if (boot != null) return boot;
    throw new ExecutionException(HaxeBundle.message("haxe.test.debug.unsupported.target"));
  }

  /** Same precedence as the plain run's launch command: the SDK-configured HashLink, then env, then PATH. */
  @NotNull
  private static Path resolveHlExecutable(HaxeTestRunConfiguration configuration) throws ExecutionException {
    Module module = ReadAction.computeBlocking(() -> buildFileModule(configuration));
    return HlExecutableResolver.resolve(module)
      .orElseThrow(() -> new ExecutionException(HaxeBundle.message("haxe.test.debug.no.hl")));
  }

  @Nullable
  private static Module buildFileModule(HaxeTestRunConfiguration configuration) {
    String buildFilePath = configuration.getBuildFilePath();
    VirtualFile file = buildFilePath == null ? null : LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    return file == null ? null : ModuleUtilCore.findModuleForFile(file, configuration.getProject());
  }

  /** The tests build's classpath roots, scoping breakpoint binding and frame resolution to THIS build's files. */
  @NotNull
  private static List<String> sourceDirectories(HaxeTestRunConfiguration configuration) {
    String buildFilePath = configuration.getBuildFilePath();
    if (StringUtil.isEmptyOrSpaces(buildFilePath)) return List.of();
    return ReadAction.computeBlocking(
      () -> HaxeBuildClasspaths.sourceDirectories(configuration.getProject(), buildFilePath));
  }
}
