package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.runner.HaxeRunConfigurationType;
import com.intellij.plugins.haxe.runner.debugger.browser.BrowserRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.browser.BrowserConfigurationFactory;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapRunConfigurationBase;
import com.intellij.plugins.haxe.runner.debugger.flash.FlashRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.flash.FlashConfigurationFactory;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HashLinkRunConfiguration;
import com.intellij.plugins.haxe.runner.debugger.hashlink.HashLinkConfigurationFactory;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij.HxcppIntellijConfigurationFactory;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij.HxcppIntellijRunConfiguration;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.buildsystem.ProjectXmlParser;
import com.intellij.plugins.haxe.v2.buildtools.HxmlProjects;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Maps a build's compilation target to the run configuration able to launch its
 * output (the tool window's Build &amp; run): HL bytecode → HashLink Application,
 * browser JS → Browser. For hxml files the target comes from the file itself;
 * for lime/openfl/hxp files from the selected target's `lime display` hxml. The
 * configuration is created once with a "Run Haxe action" build step attached and
 * matched by that step's build file afterwards, so tree launches and the
 * run-configuration dropdown stay in sync.
 *
 * CPP is wired for lime-family files only (lime names the executable after
 * {@code <app file>} regardless of -debug); for plain hxml a -debug build
 * renames the executable (Main-debug.exe), so run and debug launch different
 * artifacts — a single static executable path cannot serve both executors yet.
 */
public final class HaxeProgramLaunches {

  private HaxeProgramLaunches() {
  }

  /** The configuration flavour serving one target's output, and the factory that creates it. */
  private record LaunchSpec(@NotNull Class<? extends RunConfiguration> configurationClass,
                           @NotNull Class<? extends ConfigurationFactory> factoryClass) {
  }

  private static final LaunchSpec HASHLINK_APP = new LaunchSpec(HashLinkRunConfiguration.class, HashLinkConfigurationFactory.class);
  private static final LaunchSpec BROWSER_APP = new LaunchSpec(BrowserRunConfiguration.class, BrowserConfigurationFactory.class);
  private static final LaunchSpec FLASH_APP = new LaunchSpec(FlashRunConfiguration.class, FlashConfigurationFactory.class);
  private static final LaunchSpec HXCPP_APP = new LaunchSpec(HxcppIntellijRunConfiguration.class, HxcppIntellijConfigurationFactory.class);

  /** The single authority on which run configuration launches which target output. */
  @Nullable
  private static LaunchSpec specFor(@NotNull HaxeTarget target,
                                    @NotNull String targetOutput,
                                    @NotNull HaxeBuildFileType type) {
    String output = targetOutput.toLowerCase(Locale.ROOT);
    return switch (target) {
      // HL/C output (-hl out/main.c) is a source directory, not runnable bytecode
      case HL -> output.endsWith(".hl") ? HASHLINK_APP : null;
      case JAVA_SCRIPT -> output.endsWith(".js") ? BROWSER_APP : null;
      case FLASH -> output.endsWith(".swf") ? FLASH_APP : null;
      case CPP -> type != HaxeBuildFileType.HXML ? HXCPP_APP : null;
      default -> null;
    };
  }

  /** Display name of the configuration kind that launches this build ("HashLink Application", …), or null when unsupported. */
  @Nullable
  public static String launchKind(@NotNull HaxeBuildFileInfo info, @NotNull HaxeBuildFileType type) {
    if (info.target() == null || info.targetOutput() == null) return null;
    LaunchSpec spec = specFor(info.target(), info.targetOutput(), type);
    return spec == null ? null : HaxeRunConfigurationType.getInstance().getFactory(spec.factoryClass()).getName();
  }

  /**
   * The build's launch configuration: the registered one when present (matched by
   * target class + the build step's build file, so renames don't duplicate it),
   * else a new one added to the RunManager.
   */
  @Nullable
  public static RunnerAndConfigurationSettings findOrCreate(@NotNull Project project,
                                                            @NotNull Module module,
                                                            @NotNull HaxeBuildFile buildFile,
                                                            @NotNull HaxeTarget target,
                                                            @NotNull String targetOutput) {
    LaunchSpec spec = specFor(target, targetOutput, buildFile.type());
    if (spec == null) return null;
    VirtualFile file = buildFile.file();

    RunnerAndConfigurationSettings existing = RunManager.getInstance(project).getAllSettings().stream()
      .filter(candidate -> matches(candidate, spec.configurationClass(), file.getPath()))
      .findFirst()
      .orElse(null);
    
    if (existing != null) return existing;

    RunnerAndConfigurationSettings settings = createFor(project, buildFile, target, targetOutput);
    DapRunConfigurationBase configuration = (DapRunConfigurationBase)settings.getConfiguration();
    configuration.setModule(module);

    HaxeActionBeforeRunTaskProvider.Task buildTask = new HaxeActionBeforeRunTaskProvider.Task();
    buildTask.setBuildFilePath(file.getPath());
    buildTask.setActionName(buildActionFor(buildFile.type()));
    configuration.setBeforeRunTasks(List.of(buildTask));

    RunManager.getInstance(project).addConfiguration(settings);
    return settings;
  }

  @NotNull
  private static RunnerAndConfigurationSettings createFor(@NotNull Project project,
                                                          @NotNull HaxeBuildFile buildFile,
                                                          @NotNull HaxeTarget target,
                                                          @NotNull String targetOutput) {
    return switch (target) {
      case HL -> createHashLink(project, buildFile, targetOutput);
      case JAVA_SCRIPT -> createBrowser(project, buildFile, targetOutput);
      case FLASH -> createFlash(project, buildFile, targetOutput);
      case CPP -> createHxcppIntellij(project, buildFile, targetOutput);
      // unreachable: specFor gates every other target to null
      default -> throw new IllegalStateException("no launch configuration for target " + target);
    };
  }

  @NotNull
  private static RunnerAndConfigurationSettings createSettings(@NotNull Project project,
                                                               @NotNull String name,
                                                               @NotNull Class<? extends ConfigurationFactory> factoryClass) {
    return RunManager.getInstance(project)
      .createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(factoryClass));
  }

  @NotNull
  private static RunnerAndConfigurationSettings createHashLink(@NotNull Project project,
                                                               @NotNull HaxeBuildFile buildFile,
                                                               @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.hashlink", file.getName());
    RunnerAndConfigurationSettings settings = createSettings(project, name, HashLinkConfigurationFactory.class);
    HashLinkRunConfiguration configuration = (HashLinkRunConfiguration)settings.getConfiguration();
    if (buildFile.type() == HaxeBuildFileType.HXML) {
      configuration.setHlFilePath(resolvedOutput(file, targetOutput).toString());
    }
    else {
      configureLimeHashLink(configuration, buildFile, resolvedOutput(file, targetOutput));
    }
    return settings;
  }

  @NotNull
  private static RunnerAndConfigurationSettings createBrowser(@NotNull Project project,
                                                              @NotNull HaxeBuildFile buildFile,
                                                              @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.browser", file.getName());
    RunnerAndConfigurationSettings settings = createSettings(project, name, BrowserConfigurationFactory.class);
    BrowserRunConfiguration configuration = (BrowserRunConfiguration)settings.getConfiguration();
    // serve mode hosts the directory containing the compiled .js (plus its
    // source map and index.html); the browser is picked in the editor
    Path outputDirectory = resolvedOutput(file, targetOutput).getParent();
    if (outputDirectory != null) {
      configuration.setContentRoot(outputDirectory.toString());
    }
    return settings;
  }

  @NotNull
  private static RunnerAndConfigurationSettings createFlash(@NotNull Project project,
                                                            @NotNull HaxeBuildFile buildFile,
                                                            @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.flash", file.getName());
    RunnerAndConfigurationSettings settings = createSettings(project, name, FlashConfigurationFactory.class);
    FlashRunConfiguration configuration = (FlashRunConfiguration)settings.getConfiguration();
    configuration.setSwfFilePath(resolvedOutput(file, targetOutput).toString());
    // the flex SDK and player cannot be guessed - the visible config prompts for them
    return settings;
  }

  @NotNull
  private static RunnerAndConfigurationSettings createHxcppIntellij(@NotNull Project project,
                                                                    @NotNull HaxeBuildFile buildFile,
                                                                    @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.hxcpp", file.getName());
    RunnerAndConfigurationSettings settings = createSettings(project, name, HxcppIntellijConfigurationFactory.class);
    HxcppIntellijRunConfiguration configuration = (HxcppIntellijRunConfiguration)settings.getConfiguration();
    configureLimeHxcpp(configuration, buildFile, resolvedOutput(file, targetOutput));
    return settings;
  }

  /**
   * lime packages an HL build into {@code <export>/hl/bin}: the obj bytecode
   * becomes {@code bin/hlboot.dat}, next to a renamed copy of the hl runtime
   * ({@code <app file>[.exe]}) carrying the game's .hdll libraries. The SDK's
   * plain hl executable cannot run that game, so the configuration points at
   * hlboot.dat and overrides the runtime with the bundled executable.
   */
  private static void configureLimeHashLink(@NotNull HashLinkRunConfiguration configuration,
                                            @NotNull HaxeBuildFile buildFile,
                                            @NotNull Path objOutput) {
    // the HL output path points at a file INSIDE obj - its parent is the obj dir
    Path binDir = binDirBesideObj(objOutput.getParent());
    if (binDir == null) return;
    configuration.setHlFilePath(binDir.resolve("hlboot.dat").toString());

    String appFile = appFileName(buildFile);
    if (appFile != null) {
      configuration.setUseCustomHlBinary(true);
      configuration.setCustomHlBinaryPath(binDir.resolve(HaxeSdkUtilBase.getExecutableName(appFile)).toString());
    }
  }

  /**
   * lime places the desktop executable at {@code <export>/<target>/bin/<app file>[.exe]}.
   * The display pipeline reports that path directly; the legacy `lime display`
   * fallback yields the C++ obj directory instead — derive bin from it the way
   * the HL flavor does. Unresolvable (hxp, no app file): the visible
   * configuration prompts for the executable.
   */
  private static void configureLimeHxcpp(@NotNull HxcppIntellijRunConfiguration configuration,
                                         @NotNull HaxeBuildFile buildFile,
                                         @NotNull Path output) {
    if ("obj".equals(String.valueOf(output.getFileName()))) {
      // the CPP output path IS the obj dir; the executable sits in bin beside it
      Path binDir = binDirBesideObj(output);
      String appFile = appFileName(buildFile);
      if (binDir == null || appFile == null) return;
      output = binDir.resolve(HaxeSdkUtilBase.getExecutableName(appFile));
    }
    configuration.setExecutablePath(output.toString());
  }

  /** lime's runnable artifacts live in {@code <export>/<target>/bin}, beside the obj dir the legacy display path reports. */
  @Nullable
  private static Path binDirBesideObj(@Nullable Path objDir) {
    if (objDir == null || !"obj".equals(String.valueOf(objDir.getFileName()))) return objDir;
    Path targetDir = objDir.getParent();
    return targetDir == null ? null : targetDir.resolve("bin");
  }

  /** The {@code <app file>} name from the project xml; null for hxp (a script, not xml) or when undeclared. */
  @Nullable
  private static String appFileName(@NotNull HaxeBuildFile buildFile) {
    if (buildFile.type() == HaxeBuildFileType.HXP_PROJECT) return null;
    String content = ReadAction.computeBlocking(() -> HaxeBuildFileInspector.loadText(buildFile.file()));
    return content == null ? null : ProjectXmlParser.parseAppFile(content);
  }

  /**
   * The target output resolved the way the compiler would: against the build
   * file's directory (lime display also runs there, so its paths resolve the same).
   */
  @NotNull
  private static Path resolvedOutput(@NotNull VirtualFile buildFile, @NotNull String targetOutput) {
    return Path.of(buildFile.getParent().getPath())
      .resolve(targetOutput)
      .normalize();
  }

  /** The default build action of the file's type - what the attached build step runs. */
  @NotNull
  private static String buildActionFor(@NotNull HaxeBuildFileType type) {
    return type == HaxeBuildFileType.HXML ? HxmlProjects.BUILD_ACTION : LimeProjects.BUILD_ACTION;
  }

  private static boolean matches(@NotNull RunnerAndConfigurationSettings candidate,
                                 @NotNull Class<? extends RunConfiguration> configurationClass,
                                 @NotNull String buildFilePath) {
    RunConfiguration configuration = candidate.getConfiguration();
    if (!configurationClass.isInstance(configuration)) return false;
    return configuration.getBeforeRunTasks().stream()
      .anyMatch(task -> task instanceof HaxeActionBeforeRunTaskProvider.Task build
                        && build.getBuildFilePath().equals(buildFilePath));
  }
}
