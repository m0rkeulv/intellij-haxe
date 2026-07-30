package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
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
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
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

  /** Display name of the configuration kind that launches this build ("HashLink Application", …), or null when unsupported. */
  @Nullable
  public static String launchKind(@NotNull HaxeBuildFileInfo info, @NotNull HaxeBuildFileType type) {
    if (info.target() == null || info.targetOutput() == null) return null;
    Class<? extends RunConfiguration> configurationClass = configurationClassFor(info.target(), info.targetOutput(), type);
    if (configurationClass == HashLinkRunConfiguration.class) {
      return HaxeRunConfigurationType.getInstance().getFactory(HashLinkConfigurationFactory.class).getName();
    }
    if (configurationClass == BrowserRunConfiguration.class) {
      return HaxeRunConfigurationType.getInstance().getFactory(BrowserConfigurationFactory.class).getName();
    }
    if (configurationClass == FlashRunConfiguration.class) {
      return HaxeRunConfigurationType.getInstance().getFactory(FlashConfigurationFactory.class).getName();
    }
    if (configurationClass == HxcppIntellijRunConfiguration.class) {
      return HaxeRunConfigurationType.getInstance().getFactory(HxcppIntellijConfigurationFactory.class).getName();
    }
    return null;
  }

  @Nullable
  private static Class<? extends RunConfiguration> configurationClassFor(@NotNull HaxeTarget target,
                                                                         @NotNull String targetOutput,
                                                                         @NotNull HaxeBuildFileType type) {
    String output = targetOutput.toLowerCase(Locale.ROOT);
    return switch (target) {
      // HL/C output (-hl out/main.c) is a source directory, not runnable bytecode
      case HL -> output.endsWith(".hl") ? HashLinkRunConfiguration.class : null;
      case JAVA_SCRIPT -> output.endsWith(".js") ? BrowserRunConfiguration.class : null;
      case FLASH -> output.endsWith(".swf") ? FlashRunConfiguration.class : null;
      case CPP -> type != HaxeBuildFileType.HXML ? HxcppIntellijRunConfiguration.class : null;
      default -> null;
    };
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
    Class<? extends RunConfiguration> configurationClass = configurationClassFor(target, targetOutput, buildFile.type());
    if (configurationClass == null) return null;
    VirtualFile file = buildFile.file();

    RunnerAndConfigurationSettings existing = RunManager.getInstance(project).getAllSettings().stream()
      .filter(candidate -> matches(candidate, configurationClass, file.getPath()))
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

  /** The default build action of the file's type - what the attached build step runs. */
  @NotNull
  private static String buildActionFor(@NotNull HaxeBuildFileType type) {
    return type == HaxeBuildFileType.HXML ? HaxeCompileCommands.HXML_BUILD_ACTION : "build";
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

  @NotNull
  private static RunnerAndConfigurationSettings createFor(@NotNull Project project,
                                                          @NotNull HaxeBuildFile buildFile,
                                                          @NotNull HaxeTarget target,
                                                          @NotNull String targetOutput) {
    return switch (target) {
      case HL -> createHashLink(project, buildFile, targetOutput);
      case JAVA_SCRIPT -> createBrowser(project, buildFile.file(), targetOutput);
      case FLASH -> createFlash(project, buildFile.file(), targetOutput);
      case CPP -> createHxcppIntellij(project, buildFile, targetOutput);
      // unreachable: configurationClassFor gates every other target to null
      default -> throw new IllegalStateException("no launch configuration for target " + target);
    };
  }

  @NotNull
  private static RunnerAndConfigurationSettings createHxcppIntellij(@NotNull Project project,
                                                                    @NotNull HaxeBuildFile buildFile,
                                                                    @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.hxcpp", file.getName());
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
      .createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(HxcppIntellijConfigurationFactory.class));
    HxcppIntellijRunConfiguration configuration = (HxcppIntellijRunConfiguration)settings.getConfiguration();
    configureLimeHxcpp(configuration, buildFile, resolvedOutput(file, targetOutput));
    return settings;
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
      Path targetDir = output.getParent();
      String appFile = appFileName(buildFile);
      if (targetDir == null || appFile == null) return;
      String executable = SystemInfo.isWindows ? appFile + ".exe" : appFile;
      output = targetDir.resolve("bin").resolve(executable);
    }
    configuration.setExecutablePath(output.toString());
  }

  @NotNull
  private static RunnerAndConfigurationSettings createHashLink(@NotNull Project project,
                                                               @NotNull HaxeBuildFile buildFile,
                                                               @NotNull String targetOutput) {
    VirtualFile file = buildFile.file();
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.hashlink", file.getName());
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
      .createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(HashLinkConfigurationFactory.class));
    HashLinkRunConfiguration configuration = (HashLinkRunConfiguration)settings.getConfiguration();
    if (buildFile.type() == HaxeBuildFileType.HXML) {
      configuration.setHlFilePath(resolvedOutput(file, targetOutput).toString());
    }
    else {
      configureLimeHashLink(configuration, buildFile, resolvedOutput(file, targetOutput));
    }
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
    Path objDir = objOutput.getParent();
    Path binDir = objDir != null && "obj".equals(String.valueOf(objDir.getFileName()))
                  ? objDir.getParent().resolve("bin")
                  : objDir;
    if (binDir == null) return;
    configuration.setHlFilePath(binDir.resolve("hlboot.dat").toString());

    String appFile = appFileName(buildFile);
    if (appFile != null) {
      String executable = SystemInfo.isWindows ? appFile + ".exe" : appFile;
      configuration.setUseCustomHlBinary(true);
      configuration.setCustomHlBinaryPath(binDir.resolve(executable).toString());
    }
  }

  /** The {@code <app file>} name from the project xml; null for hxp (a script, not xml) or when undeclared. */
  @Nullable
  private static String appFileName(@NotNull HaxeBuildFile buildFile) {
    if (buildFile.type() == HaxeBuildFileType.HXP) return null;
    String content = ReadAction.compute(() -> HaxeBuildFileInspector.loadText(buildFile.file()));
    return content == null ? null : ProjectXmlParser.parseAppFile(content);
  }

  @NotNull
  private static RunnerAndConfigurationSettings createBrowser(@NotNull Project project,
                                                              @NotNull VirtualFile buildFile,
                                                              @NotNull String targetOutput) {
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.browser", buildFile.getName());
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
      .createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(BrowserConfigurationFactory.class));
    BrowserRunConfiguration configuration = (BrowserRunConfiguration)settings.getConfiguration();
    // serve mode hosts the directory containing the compiled .js (plus its
    // source map and index.html); the browser is picked in the editor
    Path outputDirectory = resolvedOutput(buildFile, targetOutput).getParent();
    if (outputDirectory != null) {
      configuration.setContentRoot(outputDirectory.toString());
    }
    return settings;
  }

  @NotNull
  private static RunnerAndConfigurationSettings createFlash(@NotNull Project project,
                                                            @NotNull VirtualFile buildFile,
                                                            @NotNull String targetOutput) {
    String name = HaxeBundle.message("haxe.toolwindow.program.configuration.name.flash", buildFile.getName());
    RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
      .createConfiguration(name, HaxeRunConfigurationType.getInstance().getFactory(FlashConfigurationFactory.class));
    FlashRunConfiguration configuration = (FlashRunConfiguration)settings.getConfiguration();
    configuration.setSwfFilePath(resolvedOutput(buildFile, targetOutput).toString());
    // the flex SDK and player cannot be guessed - the visible config prompts for them
    return settings;
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
}
