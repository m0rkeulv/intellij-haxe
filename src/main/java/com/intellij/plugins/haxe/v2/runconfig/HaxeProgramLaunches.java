package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.runner.debugger.browser.BrowserRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapRunConfigurationBase;
import com.intellij.plugins.haxe.runner.debugger.flash.AirRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.flash.FlashRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HashLinkRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij.HxcppIntellijRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.interp.InterpRunConfiguration;
import com.intellij.plugins.haxe.runner.neko.NekoRunConfiguration;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSystem;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildWorkDirectories;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
import com.intellij.plugins.haxe.v2.runconfig.HaxeLaunchKind.Parameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/// Maps a build's compilation target to the [HaxeLaunchKind] able to launch its
/// output (the tool window's Build & run) and creates or finds that
/// configuration. For hxml files the target comes from the file itself; for
/// lime/openfl/hxp files from the selected target's evaluation. A
/// configuration is created once with a "Run Haxe action" build step attached
/// and matched by that step's build file and action afterwards, so tree
/// launches and the run-configuration dropdown stay in sync; a launching
/// custom action gets a configuration of its own, keyed by its name.
///
/// CPP is wired for lime-family and nmml files only (both tools name the
/// executable after `<app file>` regardless of -debug); for plain hxml a
/// -debug build renames the executable (Main-debug.exe), so run and debug launch
/// different artifacts — a single static executable path cannot serve both
/// executors yet.
///
/// An interpreter build (`--interp`) writes no output to launch: compiling IS
/// running. Its Haxe Interpreter configuration runs the hxml the way the Build
/// action does (same working directory, same file argument), gets no build
/// step, and is matched by the hxml its arguments name.
public final class HaxeProgramLaunches {

  private HaxeProgramLaunches() {
  }

  /**
   * The single authority on which run configuration launches which target
   * output. {@code targetFlag} is the lime/nme target the file is set to
   * build (null for hxml): a swf launches as AIR only when that flag is
   * lime's air target - the output path says nothing reliable about it,
   * since the air export directory is configurable.
   */
  @Nullable
  static HaxeLaunchKind kindFor(@NotNull HaxeTarget target,
                                @NotNull String targetOutput,
                                @NotNull HaxeBuildFileType type,
                                @Nullable String targetFlag) {
    String output = targetOutput.toLowerCase(Locale.ROOT);
    return switch (target) {
      // HL/C output (-hl out/main.c) is a source directory, not runnable bytecode
      case HL -> output.endsWith(".hl") ? HaxeLaunchKind.HASHLINK : null;
      case JAVA_SCRIPT -> output.endsWith(".js") ? HaxeLaunchKind.BROWSER : null;
      case FLASH -> {
        if (!output.endsWith(".swf")) yield null;
        yield LimeProjects.isAirTarget(targetFlag) ? HaxeLaunchKind.AIR : HaxeLaunchKind.FLASH;
      }
      case CPP -> type != HaxeBuildFileType.HXML ? HaxeLaunchKind.HXCPP : null;
      // hxml runs the .n through the neko runtime; lime/nme package a launcher
      case NEKO -> type != HaxeBuildFileType.HXML || output.endsWith(".n") ? HaxeLaunchKind.NEKO : null;
      case INTERP -> HaxeLaunchKind.INTERP;
      default -> null;
    };
  }

  /** The kind that launches this build's output, or null when nothing can. */
  @Nullable
  public static HaxeLaunchKind launchKind(@NotNull Project project, @NotNull HaxeBuildFileInfo info, @NotNull HaxeBuildFile buildFile) {
    String output = launchOutput(info);
    if (info.target() == null || output == null) return null;
    return kindFor(info.target(), output, buildFile.type(), selectedTargetFlag(project, buildFile));
  }

  /** The lime/nme target flag the file is currently set to build; null for hxml, whose target is in the file itself. */
  @Nullable
  private static String selectedTargetFlag(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
    return HaxeBuildSystem.of(buildFile.type()).selectedTargetFlag(project, buildFile);
  }

  /**
   * The output a launch starts from: the build's target output, or "" for an
   * interpreter build, which writes none. Null when the build has no output.
   */
  @Nullable
  public static String launchOutput(@NotNull HaxeBuildFileInfo info) {
    return info.target() == HaxeTarget.INTERP ? "" : info.targetOutput();
  }

  /**
   * The build's own launch configuration (the Build & run row): the kind its
   * target output launches through, the file's default build action as the
   * step and the derived defaults as parameters.
   */
  @Nullable
  public static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                            @NotNull Module module,
                                                            @NotNull HaxeBuildFile buildFile,
                                                            @NotNull HaxeTarget target,
                                                            @NotNull String targetOutput) {
    HaxeLaunchKind kind = kindFor(target, targetOutput, buildFile.type(), selectedTargetFlag(project, buildFile));
    if (kind == null) return null;
    String buildAction = HaxeBuildSystem.of(buildFile.type()).defaultBuildActionName();
    return findOrCreate(project, module, buildFile, kind, targetOutput, buildAction, Map.of(), true);
  }

  /**
   * The configuration launching {@code buildFile}'s output through {@code kind}
   * after the action named {@code buildAction} compiled it: the registered one
   * when present (matched by kind + build file + action, so renames don't
   * duplicate it), else a new one added to the RunManager. Parameters: the
   * kind's defaults derived from the build file, overlaid with the non-blank
   * {@code overrides} - a blank override keeps the default live.
   */
  @NotNull
  public static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                            @NotNull Module module,
                                                            @NotNull HaxeBuildFile buildFile,
                                                            @NotNull HaxeLaunchKind kind,
                                                            @NotNull String targetOutput,
                                                            @NotNull String buildAction,
                                                            @NotNull Map<Parameter, String> overrides,
                                                            boolean injectDebugArguments) {
    VirtualFile file = buildFile.file();
    Map<Parameter, String> parameters = defaultParameters(project, buildFile, kind, targetOutput);
    overrides.forEach((parameter, value) -> {
      if (!StringUtil.isEmptyOrSpaces(value)) parameters.put(parameter, value.trim());
    });

    RunnerAndConfigurationSettings existing = RunManager.getInstance(project).getAllSettings().stream()
      .filter(candidate -> matches(candidate, kind, file.getPath(), buildAction))
      .findFirst()
      .orElse(null);
    if (existing != null) {
      // the DERIVED fields (artifact/launcher paths) follow the build file's
      // current layout - a reused configuration must not keep values baked
      // when the export layout was different
      apply(existing.getConfiguration(), parameters);
      return existing;
    }

    boolean defaultBuild = buildAction.equals(HaxeBuildSystem.of(buildFile.type()).defaultBuildActionName());
    String name = kind.configurationName(defaultBuild ? file.getName() : file.getName() + " " + buildAction);
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project).createConfiguration(name, kind.factory());
    apply(settings.getConfiguration(), parameters);
    DapRunConfigurationBase configuration = (DapRunConfigurationBase)settings.getConfiguration();
    configuration.setModule(module);

    // an empty list also drops the platform's default Build step
    List<BeforeRunTask<?>> beforeLaunch = kind.compilesBeforeLaunch()
                                          ? List.of(buildStep(buildFile, buildAction, injectDebugArguments))
                                          : List.of();
    configuration.setBeforeRunTasks(beforeLaunch);

    RunManager.getInstance(project).addConfiguration(settings);
    return settings;
  }

  /**
   * The kind's parameters as the build file's layout dictates: the artifact
   * the selected target writes and, for the lime family, the launcher packaged
   * beside it. A parameter the file gives no value for is absent, and the
   * visible configuration prompts for it.
   */
  @NotNull
  public static Map<Parameter, String> defaultParameters(@NotNull Project project,
                                                         @NotNull HaxeBuildFile buildFile,
                                                         @NotNull HaxeLaunchKind kind,
                                                         @NotNull String targetOutput) {
    Map<Parameter, String> parameters = new EnumMap<>(Parameter.class);
    switch (kind) {
      case HASHLINK -> hashLinkDefaults(project, buildFile, targetOutput, parameters);
      case BROWSER -> browserDefaults(project, buildFile, targetOutput, parameters);
      case FLASH -> parameters.put(Parameter.SWF, resolvedOutput(project, buildFile.file(), targetOutput).toString());
      case AIR -> airDefaults(project, buildFile, targetOutput, parameters);
      case HXCPP -> hxcppDefaults(project, buildFile, targetOutput, parameters);
      case NEKO -> nekoDefaults(project, buildFile, targetOutput, parameters);
      case INTERP -> interpDefaults(project, buildFile, parameters);
    }
    return parameters;
  }

  /** Writes the parameters onto a configuration of the matching kind; fields without a parameter (SDK, browser choice...) stay. */
  public static void apply(@NotNull RunConfiguration configuration, @NotNull Map<Parameter, String> parameters) {
    switch (configuration) {
      case HashLinkRunConfiguration hashLink -> applyHashLink(hashLink, parameters);
      case BrowserRunConfiguration browser -> ifPresent(parameters, Parameter.CONTENT_ROOT, browser::setContentRoot);
      case FlashRunConfiguration flash -> ifPresent(parameters, Parameter.SWF, flash::setSwfFilePath);
      case AirRunConfiguration air -> applyAir(air, parameters);
      case HxcppIntellijRunConfiguration hxcpp -> ifPresent(parameters, Parameter.EXECUTABLE, hxcpp::setExecutablePath);
      case NekoRunConfiguration neko -> applyNeko(neko, parameters);
      case InterpRunConfiguration interp -> applyInterp(interp, parameters);
      default -> { }
    }
  }

  private static void ifPresent(@NotNull Map<Parameter, String> parameters,
                                @NotNull Parameter parameter,
                                @NotNull Consumer<String> setter) {
    String value = parameters.get(parameter);
    if (value != null) setter.accept(value);
  }

  /// lime packages an HL build into `<export>/hl/bin`: the obj bytecode
  /// becomes `bin/hlboot.dat`, next to a renamed copy of the hl runtime
  /// (`<app file>[.exe]`) carrying the game's .hdll libraries. The SDK's
  /// plain hl executable cannot run that game, so the launch points at
  /// hlboot.dat and overrides the runtime with the bundled executable. An
  /// hxml's `.hl` runs on the SDK's hl.
  private static void hashLinkDefaults(@NotNull Project project,
                                       @NotNull HaxeBuildFile buildFile,
                                       @NotNull String targetOutput,
                                       @NotNull Map<Parameter, String> parameters) {
    Path output = resolvedOutput(project, buildFile.file(), targetOutput);
    if (buildFile.type() == HaxeBuildFileType.HXML) {
      parameters.put(Parameter.HL_FILE, output.toString());
      return;
    }
    // the HL output path points at a file INSIDE obj - its parent is the obj dir
    Path binDir = binDirBesideObj(output.getParent());
    if (binDir == null) return;
    parameters.put(Parameter.HL_FILE, binDir.resolve("hlboot.dat").toString());
    String appFile = appFileName(buildFile);
    if (appFile != null) {
      parameters.put(Parameter.HL_BINARY, binDir.resolve(HaxeSdkUtilBase.getExecutableName(appFile)).toString());
    }
  }

  private static void applyHashLink(@NotNull HashLinkRunConfiguration configuration, @NotNull Map<Parameter, String> parameters) {
    ifPresent(parameters, Parameter.HL_FILE, configuration::setHlFilePath);
    String binary = parameters.get(Parameter.HL_BINARY);
    if (binary != null) {
      configuration.setUseCustomHlBinary(true);
      configuration.setCustomHlBinaryPath(binary);
    }
  }

  /// Serve mode hosts the directory containing the compiled .js (plus its
  /// source map and index.html); the browser is picked in the editor.
  private static void browserDefaults(@NotNull Project project,
                                      @NotNull HaxeBuildFile buildFile,
                                      @NotNull String targetOutput,
                                      @NotNull Map<Parameter, String> parameters) {
    Path outputDirectory = resolvedOutput(project, buildFile.file(), targetOutput).getParent();
    if (outputDirectory != null) {
      parameters.put(Parameter.CONTENT_ROOT, outputDirectory.toString());
    }
  }

  /// lime's air target exports the descriptor (application.xml) at the
  /// target root with the content (swf) in `bin/` beside it, whatever the
  /// configured output directory - the target output names the swf. The
  /// flex/AIR SDK cannot be guessed; the visible configuration prompts for it.
  private static void airDefaults(@NotNull Project project,
                                  @NotNull HaxeBuildFile buildFile,
                                  @NotNull String targetOutput,
                                  @NotNull Map<Parameter, String> parameters) {
    Path binDir = resolvedOutput(project, buildFile.file(), targetOutput).getParent();
    if (binDir == null || binDir.getParent() == null) return;
    parameters.put(Parameter.DESCRIPTOR, binDir.getParent().resolve("application.xml").toString());
    parameters.put(Parameter.CONTENT_ROOT, binDir.toString());
  }

  private static void applyAir(@NotNull AirRunConfiguration configuration, @NotNull Map<Parameter, String> parameters) {
    ifPresent(parameters, Parameter.DESCRIPTOR, configuration::setDescriptorPath);
    ifPresent(parameters, Parameter.CONTENT_ROOT, configuration::setContentRootPath);
  }

  /// An nmml target output is the executable path already and passes through.
  /// lime places the desktop executable at `<export>/<target>/bin/<app file>[.exe]`;
  /// its display pipeline reports that path directly, but the legacy `lime display`
  /// fallback yields the C++ obj directory instead — derive bin from it the way
  /// the HL flavor does. Unresolvable (hxp, or an nmml without an app file):
  /// the visible configuration prompts for the executable.
  private static void hxcppDefaults(@NotNull Project project,
                                    @NotNull HaxeBuildFile buildFile,
                                    @NotNull String targetOutput,
                                    @NotNull Map<Parameter, String> parameters) {
    Path output = resolvedOutput(project, buildFile.file(), targetOutput);
    if ("obj".equals(String.valueOf(output.getFileName()))) {
      // the CPP output path IS the obj dir; the executable sits in bin beside it
      Path binDir = binDirBesideObj(output);
      String appFile = appFileName(buildFile);
      if (binDir == null || appFile == null) return;
      output = binDir.resolve(HaxeSdkUtilBase.getExecutableName(appFile));
    }
    parameters.put(Parameter.EXECUTABLE, output.toString());
  }

  /**
   * hxml: the neko runtime executes the {@code .n} bytecode (the configured
   * Build Tools neko, else the bare name from PATH). lime and nme both wrap
   * the bytecode in a launcher executable, which runs directly - lime's
   * target output points inside obj with the launcher in bin beside it
   * (same layout the hxcpp and HL configs navigate); nme's target output IS
   * the launcher.
   */
  private static void nekoDefaults(@NotNull Project project,
                                   @NotNull HaxeBuildFile buildFile,
                                   @NotNull String targetOutput,
                                   @NotNull Map<Parameter, String> parameters) {
    Path output = resolvedOutput(project, buildFile.file(), targetOutput);
    if (buildFile.type() == HaxeBuildFileType.HXML) {
      // empty executable = the neko RUNTIME, resolved at launch (settings/SDK/PATH)
      parameters.put(Parameter.EXECUTABLE, "");
      parameters.put(Parameter.PROGRAM_ARGUMENTS, output.toString());
      parameters.put(Parameter.WORKING_DIRECTORY, String.valueOf(output.getParent()));
      return;
    }
    Path launcher = output;
    if (LimeProjects.isLimeFamily(buildFile.type())) {
      // lime's target output points inside obj; the launcher is bin/<app file> beside it
      Path binDir = binDirBesideObj(output.getParent());
      String appFile = appFileName(buildFile);
      if (binDir != null && appFile != null) {
        launcher = binDir.resolve(HaxeSdkUtilBase.getExecutableName(appFile));
      }
    }
    parameters.put(Parameter.EXECUTABLE, launcher.toString());
  }

  private static void applyNeko(@NotNull NekoRunConfiguration configuration, @NotNull Map<Parameter, String> parameters) {
    ifPresent(parameters, Parameter.EXECUTABLE, configuration::setExecutablePath);
    ifPresent(parameters, Parameter.PROGRAM_ARGUMENTS, configuration::setProgramArguments);
    ifPresent(parameters, Parameter.WORKING_DIRECTORY, configuration::setWorkingDirectory);
  }

  /**
   * The hxml as the Build action runs it: its working directory (override or
   * sniffed) and the file argument relative to it. Extra arguments and the
   * interpreter checkbox stay the user's.
   */
  private static void interpDefaults(@NotNull Project project,
                                     @NotNull HaxeBuildFile buildFile,
                                     @NotNull Map<Parameter, String> parameters) {
    String workDirectory = HaxeBuildWorkDirectories.workDirectory(project, buildFile.file());
    parameters.put(Parameter.WORKING_DIRECTORY, StringUtil.notNullize(workDirectory));
    parameters.put(Parameter.COMPILER_ARGUMENTS, HaxeBuildWorkDirectories.fileArgument(buildFile.file(), workDirectory));
  }

  private static void applyInterp(@NotNull InterpRunConfiguration configuration, @NotNull Map<Parameter, String> parameters) {
    ifPresent(parameters, Parameter.WORKING_DIRECTORY, configuration::setWorkingDirectory);
    ifPresent(parameters, Parameter.COMPILER_ARGUMENTS, configuration::setCompilerArguments);
  }

  /// lime's runnable artifacts live in `<export>/<target>/bin`, beside the obj dir the legacy display path reports.
  @Nullable
  private static Path binDirBesideObj(@Nullable Path objDir) {
    if (objDir == null || !"obj".equals(String.valueOf(objDir.getFileName()))) return objDir;
    Path targetDir = objDir.getParent();
    return targetDir == null ? null : targetDir.resolve("bin");
  }

  /// The `<app file>` name the tool packages under: lime's default when a lime-family
  /// xml declares none; null for hxp (a script, not xml) or an undeclared nmml one.
  @Nullable
  private static String appFileName(@NotNull HaxeBuildFile buildFile) {
    if (buildFile.type() == HaxeBuildFileType.HXP_PROJECT) return null;
    String content = HaxeReadActions.compute(() -> HaxeBuildFileInspector.loadText(buildFile.file()));
    if (content == null) return null;
    return LimeProjects.isLimeFamily(buildFile.type()) ? LimeProjects.appFile(content) : ProjectXmlParser.parseAppFile(content);
  }

  /**
   * The target output resolved the way the compiler would: against the build
   * file's work directory - the file's own folder for the lime family (lime
   * display runs there, so its paths resolve the same), the hxml anchor from
   * {@link HaxeBuildWorkDirectories} otherwise.
   */
  @NotNull
  private static Path resolvedOutput(@NotNull Project project, @NotNull VirtualFile buildFile, @NotNull String targetOutput) {
    String anchor = HaxeBuildWorkDirectories.workDirectory(project, buildFile);
    return Path.of(anchor != null ? anchor : buildFile.getParent().getPath())
      .resolve(targetOutput)
      .normalize();
  }

  private static boolean matches(@NotNull RunnerAndConfigurationSettings candidate,
                                 @NotNull HaxeLaunchKind kind,
                                 @NotNull String buildFilePath,
                                 @NotNull String buildAction) {
    RunConfiguration configuration = candidate.getConfiguration();
    if (!kind.configurationClass().isInstance(configuration)) return false;
    return configuration instanceof InterpRunConfiguration interp
           ? namesBuildFile(interp, buildFilePath)
           : configuration.getBeforeRunTasks().stream().anyMatch(task -> buildsFile(task, buildFilePath, buildAction));
  }

  private static boolean buildsFile(@NotNull BeforeRunTask<?> task, @NotNull String buildFilePath, @NotNull String buildAction) {
    return task instanceof HaxeActionBeforeRunTaskProvider.Task build
           && build.getBuildFilePath().equals(buildFilePath)
           && build.getActionName().equals(buildAction);
  }

  /** Whether the interpreter configuration's arguments are exactly the hxml, resolved against its working directory. */
  private static boolean namesBuildFile(@NotNull InterpRunConfiguration configuration, @NotNull String buildFilePath) {
    Path workDirectory = configuration.resolveWorkingDirectory();
    String arguments = configuration.getCompilerArguments().trim();
    if (workDirectory == null || arguments.isEmpty()) return false;
    try {
      return workDirectory.resolve(arguments).normalize().equals(Path.of(buildFilePath).normalize());
    }
    catch (InvalidPathException e) {
      return false;
    }
  }

  /** The "Run Haxe action" step compiling the build file before the program launches. */
  @NotNull
  private static HaxeActionBeforeRunTaskProvider.Task buildStep(@NotNull HaxeBuildFile buildFile,
                                                                @NotNull String buildAction,
                                                                boolean injectDebugArguments) {
    HaxeActionBeforeRunTaskProvider.Task buildTask = new HaxeActionBeforeRunTaskProvider.Task();
    buildTask.setBuildFilePath(buildFile.file().getPath());
    buildTask.setActionName(buildAction);
    buildTask.setInjectDebugArguments(injectDebugArguments);
    return buildTask;
  }
}
