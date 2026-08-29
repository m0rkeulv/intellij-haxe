package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.ExecutionException;
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
import com.intellij.plugins.haxe.v2.runconfig.HaxeDebugSupport;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.testing.*;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

  /** The tests build's framework, from the SELECTED section's -lib declarations. Call inside a read action. */
  @NotNull
  static HaxeTestFramework frameworkFor(@NotNull Project project, @NotNull String buildFilePath) {
    return HaxeTestFrameworks.forBuildFile(project, buildFilePath);
  }

  /** The command to spawn, where to spawn it, the build's target, and an optional advisory shown to the user. */
  record Plan(@NotNull List<String> command,
              @Nullable String workDirectory,
              boolean singleStage,
              @NotNull HaxeTarget target,
              @Nullable String hint,
              boolean browserHosted) {
    /** The usual process-launching plan; a BROWSER-hosted plan (served page, no process) uses the canonical constructor. */
    Plan(@NotNull List<String> command,
         @Nullable String workDirectory,
         boolean singleStage,
         @NotNull HaxeTarget target,
         @Nullable String hint) {
      this(command, workDirectory, singleStage, target, hint, false);
    }
  }

  /** A browser-hosted plan's served directory (its command's only element; index.html inside is the page). */
  @NotNull
  static Path browserWebRoot(@NotNull Plan plan) {
    return Path.of(plan.command().get(0));
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

    return ParametersListUtil.join(
      frameworkArguments(project, buildFilePath, hxmlSuiteContext(project, buildFilePath), filterPattern));
  }

  /** Whether the hxml tests build compiles for flash - the adl-hosted lane needs the injected reporter (exit + stdout). */
  private static boolean isFlashHxml(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return false;
    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    return info.target() == HaxeTarget.FLASH;
  }

  @NotNull
  private static String limeCompileArguments(@NotNull Project project,
                                             @NotNull VirtualFile file,
                                             @NotNull HaxeBuildFileType type,
                                             @Nullable String filterPattern) {
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
    List<String> plain = frameworkArguments(project, file.getPath(), limeSuiteContext(targetFlag), filterPattern);
    List<String> spelled = new ArrayList<>(respell(plain, LIME_SPELLINGS));
    if ("air".equals(targetFlag)) {
      spelled.addAll(airSwfVersionFlag(project));
    }
    return ParametersListUtil.join(spelled);
  }

  /**
   * lime's air builds default to {@code -swf-version 17}, whose openfl AIR
   * extern overrides trip VerifyError #1053 under a modern AIR runtime. The
   * tests swf targets the hosting SDK's own version instead (a trailing CLI
   * flag overrides the default). No flag when no AIR SDK resolves - the run
   * would already stop at the missing adl.
   */
  @NotNull
  private static List<String> airSwfVersionFlag(@NotNull Project project) {
    String adl = HaxeToolPathResolver.resolveAdlExecutable(project);
    if (adl == null) return List.of();
    // namespaceVersion is "major.minor" (e.g. 31.0); -swf-version takes the major
    String major = AirTestHost.namespaceVersion(Path.of(adl)).split("\\.")[0];
    return List.of("--haxeflag=-swf-version " + major);
  }

  /** The run's root-suite label plus whether an IDE-provided host (adl, a served browser page) runs the tests. */
  private record SuiteContext(@Nullable String suiteName, boolean hostedTests) {
  }

  @NotNull
  private static SuiteContext limeSuiteContext(@NotNull String targetFlag) {
    return new SuiteContext(suiteLabel(limeTarget(targetFlag)), LimeProjects.isHostedTarget(targetFlag));
  }

  @NotNull
  private static SuiteContext hxmlSuiteContext(@NotNull Project project, @NotNull String buildFilePath) {
    return new SuiteContext(rootSuiteName(project, buildFilePath), isFlashHxml(project, buildFilePath));
  }

  /**
   * The tests build's framework arguments in plain hxml spelling — the
   * reporting set plus the filter. The tool-specific paths respell them (see
   * {@link #respell}).
   *
   * buddy's packaged (lime/nme) tests only report when the app's OWN main
   * honors the injected {@code -D reporter} define — a lime/nme main is the
   * Sprite, not buddy's generated main, so buddy's built-in handling of that
   * define never runs. The conditional to copy into such a TestMain lives in
   * the testProjects buddy samples; without it the run stays console-only.
   */
  @NotNull
  private static List<String> frameworkArguments(@NotNull Project project,
                                                 @NotNull String buildFilePath,
                                                 @NotNull SuiteContext suite,
                                                 @Nullable String filterPattern) {
    HaxeTestFramework framework = frameworkFor(project, buildFilePath);
    List<String> arguments = reportingArguments(project, framework, suite);
    arguments.addAll(framework.filterArgs(StringUtil.nullize(filterPattern, true)));
    return arguments;
  }

  /**
   * The framework's reporting arguments over its extracted shipped reporter.
   * The hosted lanes depend on the injected reporter: adl-hosted flash for its
   * stdout output AND the exit call, browser-hosted html5 for the console
   * transport and the completion sentinel - the live-reporting toggle cannot
   * opt a hosted build out of it.
   */
  @NotNull
  private static List<String> reportingArguments(@NotNull Project project,
                                                 @NotNull HaxeTestFramework framework,
                                                 @NotNull SuiteContext suite) {
    boolean liveReporting = suite.hostedTests() || HaxeBuildToolSettings.getInstance(project).isLiveTestReporting();
    return new ArrayList<>(framework.reportingArgs(suite.suiteName(), framework.reporterClasspath(), liveReporting));
  }

  /** A build tool's forwarding forms for the three plain-hxml flags; each flag's VALUE is appended to its form. */
  private record FlagSpellings(@NotNull String defineForm, @NotNull String classpathForm, @NotNull String macroForm) {
  }

  /**
   * lime's forwarding forms: ATTACHED defines ({@code -Dname=value}: the
   * two-word spelling trips a lime bug duplicating the value),
   * {@code --source=} for classpaths and {@code --haxeflag=} for macros (all
   * verified against lime 8.3.2).
   */
  private static final FlagSpellings LIME_SPELLINGS = new FlagSpellings("-D", "--source=", "--haxeflag=--macro ");

  /**
   * nme's forwarding forms: the tool forwards ATTACHED defines and any
   * double-dash token verbatim into its generated build.hxml (single-dash
   * haxe flags like {@code -cp} are swallowed - the classpath rides the
   * {@code --class-path} spelling; all verified against nme 7.0.64).
   */
  private static final FlagSpellings NME_SPELLINGS = new FlagSpellings("-D", "--class-path ", "--macro ");

  /** Respells plain hxml arguments into a build tool's forwarding forms. */
  @NotNull
  private static List<String> respell(@NotNull List<String> plainArguments, @NotNull FlagSpellings forms) {
    List<String> spelled = new ArrayList<>();
    for (int i = 0; i < plainArguments.size(); i++) {
      String argument = plainArguments.get(i);
      switch (argument) {
        case "-D" -> spelled.add(forms.defineForm() + plainArguments.get(++i));
        case "-cp" -> spelled.add(forms.classpathForm() + plainArguments.get(++i));
        case "--macro" -> spelled.add(forms.macroForm() + plainArguments.get(++i));
        default -> spelled.add(argument);
      }
    }
    return spelled;
  }

  /** The plan-level target a lime target flag compiles through; unknown ids fall to hxcpp, the desktop default. */
  @NotNull
  private static HaxeTarget limeTarget(@NotNull String targetFlag) {
    HaxeTarget target = LimeProjects.targetFor(targetFlag);
    return target != null ? target : HaxeTarget.CPP;
  }

  @NotNull
  private static String nmeCompileArguments(@NotNull Project project,
                                            @NotNull VirtualFile file,
                                            @Nullable String filterPattern) {
    String targetFlag = NmeProjects.selectedTargetFlag(project, file);
    // unknown target ids fall to hxcpp, nme's host-desktop default
    HaxeTarget mapped = NmeProjects.targetFor(targetFlag);
    HaxeTarget target = mapped != null ? mapped : HaxeTarget.CPP;
    SuiteContext suite = new SuiteContext(suiteLabel(target), target == HaxeTarget.FLASH);
    List<String> plain = frameworkArguments(project, file.getPath(), suite, filterPattern);
    return ParametersListUtil.join(respell(plain, NME_SPELLINGS));
  }

  /** The tree's root-suite label for a target ({@code Target: Neko}). */
  @NotNull
  private static String suiteLabel(@NotNull HaxeTarget target) {
    return "Target: " + target;
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
    return suiteLabel(target);
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

  /** Whether the tests build's target can be debugged. Call inside a read action. */
  static boolean isDebuggableTarget(@NotNull Project project, @NotNull String buildFilePath) {
    return HaxeDebugSupport.supportsTestDebug(launchTarget(project, buildFilePath));
  }

  /** The haxe target the tests build's current selection launches, or null when unresolvable. Call inside a read action. */
  @Nullable
  static HaxeTarget launchTarget(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return null;
    HaxeTarget target = HaxeBuildSystem.of(type).launchTarget(project, new HaxeBuildFile(file, type));
    // an hxml without a target flag compiles-and-runs on the interpreter
    if (target == null && type == HaxeBuildFileType.HXML) return HaxeTarget.INTERP;
    return target;
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
    return plan(project, buildFilePath, filterPattern, null, nodeExecutable(project), false);
  }

  /** The configuration's plan, single-run narrowing included. */
  @NotNull
  static Plan planFor(@NotNull HaxeTestRunConfiguration configuration) throws ExecutionException {
    return plan(configuration.getProject(), configuration.getBuildFilePath(), configuration.getFilterPattern(),
                configuration.singleRun(), nodeExecutable(configuration.getProject()), false);
  }

  @NotNull
  static Plan planForDebug(@NotNull Project project,
                           @NotNull String buildFilePath,
                           @Nullable String filterPattern) throws ExecutionException {
    return plan(project, buildFilePath, filterPattern, null, nodeExecutable(project), true);
  }

  /** The debug executor's plan: its before-run compile injects the debug additions, which for hxcpp rename the binary. */
  @NotNull
  static Plan planForDebug(@NotNull HaxeTestRunConfiguration configuration) throws ExecutionException {
    return plan(configuration.getProject(), configuration.getBuildFilePath(), configuration.getFilterPattern(),
                configuration.singleRun(), nodeExecutable(configuration.getProject()), true);
  }

  /** The js runs' node runtime: SDK-configured, else PATH; null reports as a missing runtime. */
  @Nullable
  private static String nodeExecutable(@NotNull Project project) {
    return HaxeToolPathResolver.resolveNodeExecutable(project, null);
  }

  @NotNull
  static Plan plan(@NotNull Project project,
                   @NotNull String buildFilePath,
                   @Nullable String filterPattern,
                   boolean nodeOnPath) throws ExecutionException {
    return plan(project, buildFilePath, filterPattern, null, bareNode(nodeOnPath), false);
  }

  @NotNull
  static Plan planSingle(@NotNull Project project,
                         @NotNull String buildFilePath,
                         @NotNull HaxeTestSingleRuns.SingleRun singleRun,
                         boolean nodeOnPath) throws ExecutionException {
    return plan(project, buildFilePath, null, singleRun, bareNode(nodeOnPath), false);
  }

  @Nullable
  private static String bareNode(boolean nodeOnPath) {
    return nodeOnPath ? HaxeSdkUtilBase.getExecutableName("node") : null;
  }

  @NotNull
  private static Plan plan(@NotNull Project project,
                           @NotNull String buildFilePath,
                           @Nullable String filterPattern,
                           @Nullable HaxeTestSingleRuns.SingleRun singleRun,
                           @Nullable String nodeExecutable,
                           boolean debugLaunch) throws ExecutionException {
    if (StringUtil.isEmptyOrSpaces(buildFilePath)) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.build.file"));
    }
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unresolvable", buildFilePath));
    }
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (singleRun != null && LimeProjects.isLimeFamily(type)) {
      return limeSingleRunPlan(project, file, type, singleRun, nodeExecutable, debugLaunch);
    }
    if (singleRun != null && type != HaxeBuildFileType.HXML) {
      // TODO gutter runs for nmml tests builds: nme's display mode is unverified
      throw new ExecutionException(HaxeBundle.message("haxe.test.single.unsupported.type", file.getName()));
    }
    if (LimeProjects.isLimeFamily(type)) {
      return limePlan(project, file, type, debugLaunch);
    }
    if (type == HaxeBuildFileType.NMML) {
      return nmePlan(project, file, debugLaunch);
    }
    if (type != HaxeBuildFileType.HXML) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unsupported.type", file.getName()));
    }

    HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    if (singleRun != null) {
      return singleRunPlan(project, file, info, singleRun, nodeExecutable, debugLaunch);
    }
    if (info.target() == null || info.target() == HaxeTarget.INTERP) {
      HaxeTestFramework framework = frameworkFor(project, file.getPath());
      if (!framework.supportsInterp()) {
        throw new ExecutionException(
          HaxeBundle.message("haxe.test.config.framework.no.interp", framework.libraryName()));
      }
      return singleStagePlan(project, file, filterPattern);
    }
    return artifactPlan(project, file, info, nodeExecutable, debugLaunch);
  }

  /**
   * A gutter-started run: the template compile (see {@link HaxeTestSingleRuns})
   * either IS the run (interp) or produces the redirected artifact the plan
   * launches. Mirrors the whole-build shapes, with the generated main naming
   * the hxcpp binary.
   */
  @NotNull
  private static Plan singleRunPlan(@NotNull Project project,
                                    @NotNull VirtualFile file,
                                    @NotNull HaxeBuildFileInfo info,
                                    @NotNull HaxeTestSingleRuns.SingleRun singleRun,
                                    @Nullable String nodeExecutable,
                                    boolean debugLaunch) throws ExecutionException {
    HaxeTestFramework framework = frameworkFor(project, file.getPath());
    if (framework.singleRunTemplate(singleRun.singleTest()) == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.single.unsupported", framework.libraryName()));
    }
    HaxeTarget target = info.target() != null ? info.target() : HaxeTarget.INTERP;

    if (target == HaxeTarget.INTERP) {
      if (!framework.supportsInterp()) {
        throw new ExecutionException(
          HaxeBundle.message("haxe.test.config.framework.no.interp", framework.libraryName()));
      }
      HaxeCompileCommands.Resolved resolved = singleRunCompile(project, file, framework, singleRun);
      if (resolved == null) {
        throw new ExecutionException(HaxeBundle.message("haxe.test.single.unresolvable", file.getName()));
      }
      return new Plan(resolved.command(), resolved.workDirectory(), true, HaxeTarget.INTERP, null);
    }

    Path artifact = HaxeTestSingleRuns.artifact(file, framework, singleRun, target);
    if (artifact == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.single.unresolvable", file.getName()));
    }
    String workDirectory = HaxeBuildWorkDirectories.workDirectory(project, file);
    List<String> command = singleRunCommand(project, file, artifact, target, nodeExecutable, debugLaunch);
    return new Plan(command, workDirectory, false, target, nodeHint(target, info));
  }

  /**
   * A gutter run on a lime-family tests build. The compile (the before-run
   * step, see {@link HaxeTestSingleRuns#resolveLimeCompile}) is a direct haxe
   * compile over the tool's effective arguments; its redirected artifact
   * launches through the same lanes as an hxml single run.
   */
  @NotNull
  private static Plan limeSingleRunPlan(@NotNull Project project,
                                        @NotNull VirtualFile file,
                                        @NotNull HaxeBuildFileType type,
                                        @NotNull HaxeTestSingleRuns.SingleRun singleRun,
                                        @Nullable String nodeExecutable,
                                        boolean debugLaunch) throws ExecutionException {
    HaxeTestFramework framework = frameworkFor(project, file.getPath());
    if (framework.singleRunTemplate(singleRun.singleTest()) == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.single.unsupported", framework.libraryName()));
    }
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
    if (LimeProjects.FLASH_FAMILY_TARGETS.contains(targetFlag) && !framework.supportsFlash()) {
      throw new ExecutionException(
        HaxeBundle.message("haxe.test.config.framework.no.flash", framework.libraryName()));
    }
    HaxeTarget target = limeTarget(targetFlag);
    Path artifact = HaxeTestSingleRuns.artifact(file, framework, singleRun, target);
    if (artifact == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.single.unresolvable", file.getName()));
    }
    if (LimeProjects.BROWSER_TARGETS.contains(targetFlag)) {
      // the single-run js runs in a browser page like the whole build (the
      // openfl/lime code it carries expects the DOM, which node lacks); the
      // generated harness beside the artifact is the served page
      Path webRoot = HaxeTestSingleRuns.browserHarnessRoot(artifact);
      if (webRoot == null) {
        throw new ExecutionException(HaxeBundle.message("haxe.test.single.unresolvable", file.getName()));
      }
      return new Plan(List.of(webRoot.toString()), webRoot.toString(), false, HaxeTarget.JAVA_SCRIPT, null, true);
    }
    List<String> command = singleRunCommand(project, file, artifact, target, nodeExecutable, debugLaunch);
    return new Plan(command, file.getParent().getPath(), false, target, null);
  }

  /** The launch command over a single run's redirected artifact - shared by the hxml and lime shapes. */
  @NotNull
  private static List<String> singleRunCommand(@NotNull Project project,
                                               @NotNull VirtualFile file,
                                               @NotNull Path artifact,
                                               @NotNull HaxeTarget target,
                                               @Nullable String nodeExecutable,
                                               boolean debugLaunch) throws ExecutionException {
    return switch (target) {
      case HL -> hlCommand(project, file, artifact, target);
      case NEKO -> List.of(nekoExecutable(project), artifact.toString());
      case JAVA -> jvmCommand(artifact, target);
      case JAVA_SCRIPT -> nodeCommand(artifact, nodeExecutable);
      case CPP -> List.of(singleRunCppBinary(project, file, artifact, debugLaunch).toString());
      case FLASH -> adlCommand(project, artifact, debugLaunch);
      default -> throw unrunnableTarget(target);
    };
  }

  /** The single-run compile for the before-run step; null when unresolvable. Call in a read action. */
  @Nullable
  static HaxeCompileCommands.Resolved singleRunCompile(@NotNull Project project,
                                                       @NotNull VirtualFile file,
                                                       @NotNull HaxeTestFramework framework,
                                                       @NotNull HaxeTestSingleRuns.SingleRun singleRun) {
    return HaxeTestSingleRuns.resolveCompile(
      project, file, framework, singleRunCompileArguments(project, file.getPath(), framework, singleRun), singleRun);
  }

  /** The framework arguments a single run compiles with: the reporting set plus the method narrowing. */
  @NotNull
  private static String singleRunCompileArguments(@NotNull Project project,
                                                  @NotNull String buildFilePath,
                                                  @NotNull HaxeTestFramework framework,
                                                  @NotNull HaxeTestSingleRuns.SingleRun singleRun) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    HaxeBuildFileType type = file == null || !file.isValid() ? null : HaxeBuildFileScanner.detectType(project, file);
    SuiteContext suite = suiteContextFor(project, buildFilePath, file, type);
    List<String> arguments = reportingArguments(project, framework, suite);
    if (singleRun.singleTest()) {
      arguments.addAll(framework.singleRunFilterArgs(singleRun.testMethod()));
    }
    return ParametersListUtil.join(arguments);
  }

  private static SuiteContext suiteContextFor(@NotNull Project project, @NotNull String buildFilePath,
                                              @Nullable VirtualFile file, @Nullable HaxeBuildFileType type) {
    if (LimeProjects.isLimeFamily(type)) {
      return limeSuiteContext(LimeProjects.selectedTargetFlag(project, type, file));
    }
    return hxmlSuiteContext(project, buildFilePath);
  }

  /**
   * The lime-family single-run compile over pre-fetched display arguments
   * (fetched OUTSIDE the read lock - the display mode spawns the tool); null
   * when unresolvable. Call in a read action.
   */
  @Nullable
  static HaxeCompileCommands.Resolved singleRunLimeCompile(@NotNull Project project,
                                                           @NotNull VirtualFile file,
                                                           @NotNull HaxeTestFramework framework,
                                                           @NotNull HaxeTestSingleRuns.SingleRun singleRun,
                                                           @NotNull List<String> effectiveArguments) {
    String extraArguments = singleRunCompileArguments(project, file.getPath(), framework, singleRun);
    return HaxeTestSingleRuns.resolveLimeCompile(project, file, framework, extraArguments, singleRun, effectiveArguments);
  }

  /** The generated main's hxcpp binary inside the redirected output directory. */
  @NotNull
  private static Path singleRunCppBinary(@NotNull Project project,
                                         @NotNull VirtualFile file,
                                         @NotNull Path outputDirectory,
                                         boolean debugLaunch) {
    String effective = HaxeBuildSections.selectedSectionContent(project, new HaxeBuildFile(file, HaxeBuildFileType.HXML));
    boolean debugBuild = debugLaunch || (effective != null && HxmlFileParser.hasDebugFlag(effective));
    return HxcppBinaries.binary(outputDirectory, HaxeTestSingleRuns.MAIN_CLASS, debugBuild);
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
                               @NotNull HaxeBuildFileType type,
                               boolean debugLaunch) throws ExecutionException {
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
    String content = HaxeBuildFileInspector.loadText(file);
    if (LimeProjects.FLASH_FAMILY_TARGETS.contains(targetFlag)) {
      Path swf = content == null ? null : LimeProjects.packagedSwf(file, content, targetFlag);
      if (swf == null) {
        throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.app.file", file.getName()));
      }
      List<String> command = flashCommand(project, file, swf, debugLaunch);
      return new Plan(command, swf.getParent().toString(), false, HaxeTarget.FLASH, null);
    }
    if (LimeProjects.BROWSER_TARGETS.contains(targetFlag)) {
      // the packaged output (lime's own index.html inside) is served and
      // console-captured through the browser backend. Existence is NOT
      // checked here: plans also answer configuration validation, which runs
      // before the before-run compile ever produced the directory
      Path webRoot = content == null ? null : LimeProjects.packagedWebRoot(file, content, targetFlag);
      if (webRoot == null) {
        throw new ExecutionException(HaxeBundle.message("haxe.test.config.unresolvable", file.getName()));
      }
      return new Plan(List.of(webRoot.toString()), webRoot.toString(), false, HaxeTarget.JAVA_SCRIPT, null, true);
    }
    Path binary = content == null ? null : LimeProjects.packagedBinary(file, content, targetFlag);
    if (binary == null) {
      boolean unrunnableTarget = !LimeProjects.HOST_LAUNCHABLE_TARGETS.contains(targetFlag);
      if (unrunnableTarget) {
        throw new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", targetFlag));
      }
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.app.file", file.getName()));
    }
    String workDirectory = binary.getParent().toString();
    return new Plan(List.of(binary.toString()), workDirectory, false, limeTarget(targetFlag), null);
  }

  /** An nmml tests build: compile through the nme tool, launch the packaged desktop/neko artifact. */
  @NotNull
  private static Plan nmePlan(@NotNull Project project,
                              @NotNull VirtualFile file,
                              boolean debugLaunch) throws ExecutionException {
    String targetFlag = NmeProjects.selectedTargetFlag(project, file);
    String content = HaxeBuildFileInspector.loadText(file);
    String appFile = content == null ? null : ProjectXmlParser.parseAppFile(content);
    if (appFile == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.app.file", file.getName()));
    }
    // content is non-null here: a null load already failed the appFile guard
    String appPath = ProjectXmlParser.parseAppPath(content);
    String outputRoot = appPath != null ? appPath : "bin";
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact(targetFlag, appFile, outputRoot);
    if (artifact == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", targetFlag));
    }
    Path binary = Path.of(file.getParent().getPath())
      .resolve(artifact.relativeOutput())
      .normalize();
    if (artifact.target() == HaxeTarget.FLASH) {
      List<String> command = flashCommand(project, file, binary, debugLaunch);
      return new Plan(command, binary.getParent().toString(), false, HaxeTarget.FLASH, null);
    }
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
                                   @Nullable String nodeExecutable,
                                   boolean debugLaunch) throws ExecutionException {
    HaxeTarget target = info.target();
    if (info.targetOutput() == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.output", file.getName()));
    }
    // resolved the way the compiler resolves it: against the build file's work directory
    String anchor = HaxeBuildWorkDirectories.workDirectory(project, file);
    if (anchor == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.unresolvable", file.getName()));
    }
    Path artifact = Path.of(anchor)
      .resolve(info.targetOutput())
      .normalize();
    String workDirectory = anchor;

    List<String> command = switch (target) {
      case HL -> hlCommand(project, file, artifact, target);
      case NEKO -> List.of(nekoExecutable(project), artifact.toString());
      case JAVA -> jvmCommand(artifact, target);
      case JAVA_SCRIPT -> nodeCommand(artifact, nodeExecutable);
      case CPP -> List.of(cppExecutable(project, file, artifact, debugLaunch).toString());
      case FLASH -> flashCommand(project, file, artifact, debugLaunch);
      default -> throw unrunnableTarget(target);
    };
    return new Plan(command, workDirectory, false, target, nodeHint(target, info));
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
  private static List<String> nodeCommand(@NotNull Path artifact, @Nullable String nodeExecutable) throws ExecutionException {
    if (nodeExecutable == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.node"));
    }
    return List.of(nodeExecutable, artifact.toString());
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

  /** The hxnodejs hint for a js run whose build carries no node signal; null otherwise. */
  @Nullable
  private static String nodeHint(@NotNull HaxeTarget target, @NotNull HaxeBuildFileInfo info) {
    if (target == HaxeTarget.JAVA_SCRIPT && !hasNodeSignal(info)) {
      return HaxeBundle.message("haxe.test.config.hxnodejs.hint");
    }
    return null;
  }

  /** A tests build carrying hxnodejs or -D nodejs declares node-runnability (utest then reports to stdout and exits properly). */
  private static boolean hasNodeSignal(@NotNull HaxeBuildFileInfo info) {
    boolean hasLib = info.libraries().stream()
      .anyMatch(library -> library.name().equalsIgnoreCase("hxnodejs"));
    return hasLib || info.defines().stream().anyMatch(define -> define.name().equals("nodejs"));
  }

  /** The whole-build flash launch: {@link #adlCommand} behind the framework's flash-support gate. */
  @NotNull
  private static List<String> flashCommand(@NotNull Project project,
                                           @NotNull VirtualFile file,
                                           @NotNull Path artifact,
                                           boolean debugLaunch) throws ExecutionException {
    HaxeTestFramework framework = frameworkFor(project, file.getPath());
    if (!framework.supportsFlash()) {
      throw new ExecutionException(
        HaxeBundle.message("haxe.test.config.framework.no.flash", framework.libraryName()));
    }
    return adlCommand(project, artifact, debugLaunch);
  }

  /**
   * Flash-family tests run under {@code adl -nodebug} (see {@link AirTestHost}):
   * native trace reaches stdout and the injected reporter exits the app. A
   * DEBUG launch keeps adl's default mode instead - the {@code -debug} swf
   * dials the waiting fdb, and the traces arrive on fdb's console. Requires
   * a Flex/AIR SDK (the runtimes chain) or an AIR_SDK environment variable
   * pointing at one.
   */
  @NotNull
  private static List<String> adlCommand(@NotNull Project project,
                                         @NotNull Path artifact,
                                         boolean debugLaunch) throws ExecutionException {
    if (!artifact.toString().toLowerCase(Locale.ROOT).endsWith(".swf")) {
      throw unrunnableTarget(HaxeTarget.FLASH);
    }
    String adl = HaxeToolPathResolver.resolveAdlExecutable(project);
    if (adl == null) {
      throw new ExecutionException(HaxeBundle.message("haxe.test.config.no.adl"));
    }
    Path descriptor = AirTestHost.descriptorFor(artifact, AirTestHost.namespaceVersion(Path.of(adl)));
    Path contentRoot = artifact.getParent() != null ? artifact.getParent() : artifact;
    return AirTestHost.command(adl, descriptor, contentRoot, debugLaunch);
  }

  @NotNull
  private static ExecutionException unrunnableTarget(@NotNull HaxeTarget target) {
    return new ExecutionException(HaxeBundle.message("haxe.test.config.unrunnable.target", target.toString()));
  }
}
