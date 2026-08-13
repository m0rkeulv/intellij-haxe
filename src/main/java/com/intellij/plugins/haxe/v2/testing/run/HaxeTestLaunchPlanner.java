package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HlExecutableResolver;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.testing.UtestFramework;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Turns a tests build file into the process a unit-test run spawns. Two shapes:
///
/// - **interp** (or no target): the compile IS the run - one `haxe` process with
///   the framework's reporting defines appended; its stdout carries the TeamCity
///   service messages.
/// - **artifact targets** (HL, Neko, JVM jar, desktop C++, JS under node): the
///   before-run compile step has already produced the artifact; the plan is the
///   host command launching it.
///
/// Phase 1 gate: hxml tests files and host-executable targets only - everything
/// else raises a bundle-keyed [ExecutionException]. Call inside a read action.
final class HaxeTestLaunchPlanner {

  // TODO: pick the framework from the tests build's -libs once more than utest exists (Phase 4)
  static final UtestFramework FRAMEWORK = new UtestFramework();

  /** The command to spawn, where to spawn it, the build's target, and an optional advisory shown to the user. */
  record Plan(@NotNull List<String> command,
              @Nullable String workDirectory,
              boolean singleStage,
              @NotNull HaxeTarget target,
              @Nullable String hint) {
  }

  private HaxeTestLaunchPlanner() {
  }

  /**
   * The framework's compile arguments (reporting + optional filter) as an
   * extra-arguments string, in the spelling the build tool takes: plain haxe
   * flags for hxml builds; for lime-family builds the tool's forwarding forms —
   * ATTACHED defines ({@code -Dname=value}: the two-word spelling trips a lime
   * bug duplicating the value), {@code --source=} for the classpath and
   * {@code --haxeflag=} for the macro (all verified against lime 8.3.2).
   */
  @NotNull
  static String compileArguments(@NotNull Project project,
                                 @NotNull String buildFilePath,
                                 @Nullable String filterPattern) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    HaxeBuildFileType type = file == null || !file.isValid() ? null : HaxeBuildFileScanner.detectType(project, file);
    if (LimeProjects.isLimeFamily(type)) {
      return limeCompileArguments(project, file, type, filterPattern);
    }
    if (type == HaxeBuildFileType.NMML) {
      return nmeCompileArguments(project, file, filterPattern);
    }

    List<String> arguments = new ArrayList<>(FRAMEWORK.activationArgs());
    arguments.addAll(FRAMEWORK.filterArgs(StringUtil.nullize(filterPattern, true)));
    // utest's reporter derives its root suite name from a target #if chain
    // that lacks several targets (an HL run reads "Target: Undefined") - the
    // teamcity_suite_name define overrides it with the build's real target
    String suiteName = rootSuiteName(project, buildFilePath);
    if (suiteName != null) {
      arguments.add("-D");
      arguments.add("teamcity_suite_name=" + suiteName);
    }
    // per-test event streaming: a --macro patches utest's Runner to attach the
    // shipped LiveReporter; utest's batch report stays as the fallback and the
    // events converter deduplicates it (see resources/testing/utestLiveReporter)
    if (HaxeBuildToolSettings.getInstance(project).isLiveTestReporting()) {
      HaxeTestReporterFiles.classpath().ifPresent(path -> {
        arguments.add("-cp");
        arguments.add(path);
        arguments.add("--macro");
        arguments.add("intellij_utest.Macro.init()");
      });
    }
    return ParametersListUtil.join(arguments);
  }

  @NotNull
  private static String limeCompileArguments(@NotNull Project project,
                                             @NotNull VirtualFile file,
                                             @NotNull HaxeBuildFileType type,
                                             @Nullable String filterPattern) {
    List<String> arguments = new ArrayList<>();
    arguments.add("-Dteamcity");
    String filter = StringUtil.nullize(filterPattern, true);
    if (filter != null) {
      arguments.add("-DUTEST_PATTERN=" + filter);
    }
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
    arguments.add("-Dteamcity_suite_name=Target: " + limeTarget(targetFlag));
    if (HaxeBuildToolSettings.getInstance(project).isLiveTestReporting()) {
      HaxeTestReporterFiles.classpath().ifPresent(path -> {
        arguments.add("--source=" + path);
        arguments.add("--haxeflag=--macro intellij_utest.Macro.init()");
      });
    }
    return ParametersListUtil.join(arguments);
  }

  /** The plan-level target a lime target flag compiles through; desktop platform words all mean hxcpp. */
  @NotNull
  private static HaxeTarget limeTarget(@NotNull String targetFlag) {
    return switch (targetFlag) {
      case "neko" -> HaxeTarget.NEKO;
      case "hl" -> HaxeTarget.HL;
      default -> HaxeTarget.CPP;
    };
  }

  /**
   * The nme tool forwards ATTACHED defines and any double-dash token verbatim
   * into its generated build.hxml (single-dash haxe flags like {@code -cp} are
   * swallowed - the classpath rides the {@code --class-path} spelling; all
   * verified against nme 7.0.64).
   */
  @NotNull
  private static String nmeCompileArguments(@NotNull Project project,
                                            @NotNull VirtualFile file,
                                            @Nullable String filterPattern) {
    List<String> arguments = new ArrayList<>();
    arguments.add("-Dteamcity");
    String filter = StringUtil.nullize(filterPattern, true);
    if (filter != null) {
      arguments.add("-DUTEST_PATTERN=" + filter);
    }
    String targetFlag = NmeProjects.selectedTargetFlag(project, file);
    HaxeTarget target = targetFlag.equals("neko") ? HaxeTarget.NEKO : HaxeTarget.CPP;
    arguments.add("-Dteamcity_suite_name=Target: " + target);
    if (HaxeBuildToolSettings.getInstance(project).isLiveTestReporting()) {
      HaxeTestReporterFiles.classpath().ifPresent(path -> {
        arguments.add("--class-path " + path);
        arguments.add("--macro intellij_utest.Macro.init()");
      });
    }
    return ParametersListUtil.join(arguments);
  }

  /** The tree's root suite label, from the tests build's target. Null when the file cannot be inspected. */
  @Nullable
  private static String rootSuiteName(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type != HaxeBuildFileType.HXML) return null;
    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, type));
    HaxeTarget target = info.target() != null ? info.target() : HaxeTarget.INTERP;
    return "Target: " + target;
  }

  /**
   * Whether the tests build runs as a single compile-and-run process (interp / no
   * target). Unresolvable files count as single-stage so no compile step is
   * attached - checkConfiguration reports the real problem.
   */
  static boolean isSingleStage(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return true;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (LimeProjects.isLimeFamily(type) || type == HaxeBuildFileType.NMML) return false;
    if (type != HaxeBuildFileType.HXML) return true;
    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    return info.target() == null || info.target() == HaxeTarget.INTERP;
  }

  /** The targets {@link HaxeTestDebugRunner} has a lane for. */
  private static final Set<HaxeTarget> DEBUGGABLE_TARGETS = Set.of(HaxeTarget.INTERP, HaxeTarget.HL, HaxeTarget.CPP);

  /** Whether the tests build's target can be debugged. Call inside a read action. */
  static boolean isDebuggableTarget(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return false;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (LimeProjects.isLimeFamily(type)) {
      // desktop builds debug through the hxcpp lane (the debug additions
      // inject the server haxelib through lime's --haxelib override); the HL
      // package debugs through its own bundled runtime
      HaxeTarget target = limeTarget(LimeProjects.selectedTargetFlag(project, type, file));
      return target == HaxeTarget.CPP || target == HaxeTarget.HL;
    }
    // TODO Phase 3 remainder: nme desktop-cpp tests should debug the same way
    //  (its debug additions inject the server lib too) - enable after a sandbox
    //  verification on a real nme project
    if (type != HaxeBuildFileType.HXML) return false;
    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    HaxeTarget target = info.target() != null ? info.target() : HaxeTarget.INTERP;
    return DEBUGGABLE_TARGETS.contains(target);
  }

  /** The lime HL package's bytecode ({@code hlboot.dat} beside the bundled runtime); null for any other plan shape. */
  @Nullable
  static Path packagedHlBoot(@NotNull Plan plan) {
    boolean packagedHl = plan.target() == HaxeTarget.HL && !plan.singleStage() && plan.command().size() == 1;
    if (!packagedHl) return null;
    Path runtime = Path.of(plan.command().get(0));
    Path binDirectory = runtime.getParent();
    return binDirectory == null ? null : binDirectory.resolve("hlboot.dat");
  }

  @NotNull
  static Plan plan(@NotNull Project project,
                   @NotNull String buildFilePath,
                   @Nullable String filterPattern) throws ExecutionException {
    return plan(project, buildFilePath, filterPattern, nodeOnPath(), false);
  }

  /** The debug executor's plan: its before-run compile injects the debug additions, which for hxcpp rename the binary. */
  @NotNull
  static Plan planForDebug(@NotNull Project project,
                           @NotNull String buildFilePath,
                           @Nullable String filterPattern) throws ExecutionException {
    return plan(project, buildFilePath, filterPattern, nodeOnPath(), true);
  }

  private static boolean nodeOnPath() {
    return PathEnvironmentVariableUtil.findInPath(HaxeSdkUtilBase.getExecutableName("node")) != null;
  }

  @NotNull
  static Plan plan(@NotNull Project project,
                   @NotNull String buildFilePath,
                   @Nullable String filterPattern,
                   boolean nodeOnPath) throws ExecutionException {
    return plan(project, buildFilePath, filterPattern, nodeOnPath, false);
  }

  @NotNull
  private static Plan plan(@NotNull Project project,
                           @NotNull String buildFilePath,
                           @Nullable String filterPattern,
                           boolean nodeOnPath,
                           boolean debugLaunch) throws ExecutionException {
    if (StringUtil.isEmptyOrSpaces(buildFilePath)) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.build.file"));
    }
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unresolvable", buildFilePath));
    }
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (LimeProjects.isLimeFamily(type)) {
      return limePlan(project, file, type);
    }
    if (type == HaxeBuildFileType.NMML) {
      return nmePlan(project, file);
    }
    if (type != HaxeBuildFileType.HXML) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unsupported.type", file.getName()));
    }

    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    if (info.target() == null || info.target() == HaxeTarget.INTERP) {
      return singleStagePlan(project, file, filterPattern);
    }
    return artifactPlan(project, file, info, nodeOnPath, debugLaunch);
  }

  /**
   * A lime-family tests build: the before-run step compiles through the lime
   * tool (the injection rides {@link #limeCompileArguments}); the plan launches
   * the packaged host binary. The lime HL package bundles its own runtime, so
   * even the HL app launches directly - but for the same reason it is not the
   * bare {@code [hl, artifact]} shape the HL debug lane attaches to.
   */
  @NotNull
  private static Plan limePlan(@NotNull Project project,
                               @NotNull VirtualFile file,
                               @NotNull HaxeBuildFileType type) throws ExecutionException {
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
    String content = HaxeBuildFileInspector.loadText(file);
    Path binary = content == null ? null : LimeProjects.packagedBinary(file, content, targetFlag);
    if (binary == null) {
      boolean unrunnableTarget = !LimeProjects.HOST_LAUNCHABLE_TARGETS.contains(targetFlag);
      if (unrunnableTarget) {
        // TODO Phase 3 remainder: html5 tests through the browser backend's CDP console capture
        throw new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", targetFlag));
      }
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.app.file", file.getName()));
    }
    String workDirectory = binary.getParent().toString();
    return new Plan(List.of(binary.toString()), workDirectory, false, limeTarget(targetFlag), null);
  }

  /** An nmml tests build: compile through the nme tool, launch the packaged desktop/neko artifact. */
  @NotNull
  private static Plan nmePlan(@NotNull Project project, @NotNull VirtualFile file) throws ExecutionException {
    String targetFlag = NmeProjects.selectedTargetFlag(project, file);
    String content = HaxeBuildFileInspector.loadText(file);
    String appFile = content == null ? null : ProjectXmlParser.parseAppFile(content);
    if (appFile == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.app.file", file.getName()));
    }
    String appPath = content == null ? null : ProjectXmlParser.parseAppPath(content);
    String outputRoot = appPath != null ? appPath : "bin";
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact(targetFlag, appFile, outputRoot);
    boolean hostLaunchable = artifact != null && artifact.target() != HaxeTarget.FLASH;
    if (!hostLaunchable) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", targetFlag));
    }
    Path binary = Path.of(file.getParent().getPath())
      .resolve(artifact.relativeOutput())
      .normalize();
    return new Plan(List.of(binary.toString()), binary.getParent().toString(), false, artifact.target(), null);
  }

  /**
   * The interp shape: the resolved compile command with the framework defines
   * appended. Deliberately NOT routed through the compilation server - with
   * `--connect` the code would execute inside the server process and the test
   * output would never reach this run's console.
   */
  @NotNull
  private static Plan singleStagePlan(@NotNull Project project,
                                      @NotNull VirtualFile file,
                                      @Nullable String filterPattern) throws ExecutionException {
    HaxeCompileCommands.Resolved resolved = HaxeCompileCommands.resolveAction(
      project, file.getPath(), HaxeBuildFileActions.defaultBuildActionName(HaxeBuildFileType.HXML),
      compileArguments(project, file.getPath(), filterPattern));
    if (resolved == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unresolvable", file.getName()));
    }
    // a multi-section hxml runs only its selected --next section, so the
    // appended reporting arguments belong to that section (haxe hands
    // trailing CLI arguments to the chain's LAST section otherwise)
    List<String> command = HxmlProjects.scopeToSelectedSection(project, file, resolved.command());
    return new Plan(command, resolved.workDirectory(), true, HaxeTarget.INTERP, null);
  }

  @NotNull
  private static Plan artifactPlan(@NotNull Project project,
                                   @NotNull VirtualFile file,
                                   @NotNull HaxeBuildFileInfo info,
                                   boolean nodeOnPath,
                                   boolean debugLaunch) throws ExecutionException {
    HaxeTarget target = info.target();
    if (info.targetOutput() == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.output", file.getName()));
    }
    // resolved the way the compiler resolves it: against the build file's directory
    Path artifact = Path.of(file.getParent().getPath())
      .resolve(info.targetOutput())
      .normalize();
    String workDirectory = file.getParent().getPath();

    List<String> command = switch (target) {
      case HL -> hlCommand(project, file, artifact, target);
      case NEKO -> List.of(nekoExecutable(project), artifact.toString());
      case JAVA -> jvmCommand(artifact, target);
      case JAVA_SCRIPT -> nodeCommand(artifact, nodeOnPath);
      case CPP -> List.of(cppExecutable(project, file, artifact, debugLaunch).toString());
      default -> throw unrunnableTarget(target);
    };
    String hint = target == HaxeTarget.JAVA_SCRIPT && !hasNodeSignal(info)
                  ? HaxeBundle.message("haxe.test.config.hxnodejs.hint")
                  : null;
    return new Plan(command, workDirectory, false, target, hint);
  }

  /** The plan's HL artifact (the {@code [hl, <file>.hl]} launch shape); null when the plan runs anything else. */
  @Nullable
  static Path hlArtifact(@NotNull Plan plan) {
    boolean hlLaunch = plan.target() == HaxeTarget.HL && plan.command().size() == 2;
    return hlLaunch ? Path.of(plan.command().get(1)) : null;
  }

  @NotNull
  private static List<String> hlCommand(@NotNull Project project,
                                        @NotNull VirtualFile file,
                                        @NotNull Path artifact,
                                        @NotNull HaxeTarget target) throws ExecutionException {
    // HL/C output (-hl out/main.c) is a source directory, not runnable bytecode
    if (!artifact.toString().toLowerCase(Locale.ROOT).endsWith(".hl")) {
      throw unrunnableTarget(target);
    }
    // the SDK-configured HashLink (then env, then PATH) - a bare "hl" only works
    // for users who happen to have it on PATH
    Module module = ModuleUtilCore.findModuleForFile(file, project);
    String executable = HlExecutableResolver.resolve(module)
      .map(Path::toString)
      .orElse(HaxeSdkUtilBase.getExecutableName("hl"));
    return List.of(executable, artifact.toString());
  }

  /** The resolved neko runtime: Build Tools setting, SDK, then PATH. */
  @NotNull
  private static String nekoExecutable(@NotNull Project project) {
    return HaxeToolPathResolver.resolveNekoExecutable(project, null);
  }

  @NotNull
  private static List<String> jvmCommand(@NotNull Path artifact, @NotNull HaxeTarget target) throws ExecutionException {
    // only the --jvm single-jar flavor runs directly; --java emits a source directory
    if (!artifact.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
      throw unrunnableTarget(target);
    }
    return List.of(HaxeSdkUtilBase.getExecutableName("java"), "-jar", artifact.toString());
  }

  @NotNull
  private static List<String> nodeCommand(@NotNull Path artifact, boolean nodeOnPath) throws ExecutionException {
    if (!nodeOnPath) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.node"));
    }
    return List.of(HaxeSdkUtilBase.getExecutableName("node"), artifact.toString());
  }

  /**
   * The hxcpp binary the compile arguments produce, derived deterministically:
   * a {@code -debug} in the effective hxml renames the binary even for plain
   * runs, and the debug executor's compile additions do the same for debug
   * sessions — never guessed from what happens to sit on disk (a stale
   * leftover of the other flavor must not be launched).
   */
  @NotNull
  private static Path cppExecutable(@NotNull Project project,
                                    @NotNull VirtualFile file,
                                    @NotNull Path outputDirectory,
                                    boolean debugLaunch) throws ExecutionException {
    String effective = HaxeBuildSections.selectedSectionContent(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    String mainClass = effective == null ? null : HxmlFileParser.mainClass(effective);
    if (mainClass == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.main", file.getName()));
    }
    boolean debugBuild = debugLaunch || (effective != null && HxmlFileParser.hasDebugFlag(effective));
    return HxcppBinaries.binary(outputDirectory, StringUtil.getShortName(mainClass), debugBuild);
  }

  /** A tests build carrying hxnodejs or -D nodejs declares node-runnability (utest then reports to stdout and exits properly). */
  private static boolean hasNodeSignal(@NotNull HaxeBuildFileInfo info) {
    boolean hasLib = info.libraries().stream()
      .anyMatch(library -> library.name().equalsIgnoreCase("hxnodejs"));
    return hasLib || info.defines().stream().anyMatch(define -> define.name().equals("nodejs"));
  }

  @NotNull
  private static ExecutionException unrunnableTarget(@NotNull HaxeTarget target) {
    return new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", target.toString()));
  }
}
