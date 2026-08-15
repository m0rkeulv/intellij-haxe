package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildsystem.ProjectXmlParser;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeTargetOptions;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeTargetSelectionStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facts about lime-family projects (lime/openfl xml and lime HXP scripts):
 * which build-file types the lime tool owns, which CLI tool builds them and
 * the commands it accepts. The hxml counterpart is {@link HxmlProjects}.
 */
public final class LimeProjects {

  /** The lime tool's built-in actions, offered for every lime-family build file. */
  public static final List<String> DEFAULT_ACTIONS = List.of("test", "run", "build", "clean");

  /** The default action a lime-family file compiles with. */
  public static final String BUILD_ACTION = "build";

  private LimeProjects() {
  }

  /** True for the types the lime tool can build; a plain hxp SCRIPT is not one of them. */
  public static boolean isLimeFamily(@Nullable HaxeBuildFileType type) {
    return type == HaxeBuildFileType.OPENFL || type == HaxeBuildFileType.LIME || type == HaxeBuildFileType.HXP_PROJECT;
  }

  /** The CLI tool that builds the file: openfl projects go through the openfl wrapper, everything else through lime. */
  @NotNull
  public static String toolFor(@NotNull HaxeBuildFileType type) {
    return type == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
  }

  /** The file's currently selected lime target as the tool's target flag (e.g. "html5", "windows"). */
  @NotNull
  public static String selectedTargetFlag(@NotNull Project project,
                                          @NotNull HaxeBuildFileType type,
                                          @NotNull VirtualFile file) {
    return HaxeTargetOptions.targetFlagFor(type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
  }

  /** The selected target's full flag list — the target word plus configured extras (e.g. "-64"). */
  @NotNull
  public static List<String> selectedTargetFlags(@NotNull Project project,
                                                 @NotNull HaxeBuildFileType type,
                                                 @NotNull VirtualFile file) {
    return HaxeTargetOptions.targetFlagsFor(type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
  }

  /// A `haxelib run lime|openfl …` invocation. The lime tool forwards a
  /// trailing `--connect <port>` pair into the haxe builds it generates
  /// (CommandLineTools.hx treats --connect as a haxeflag whose next argument is
  /// captured with it), so these commands can use the compilation server too.
  public static boolean isToolCommand(@NotNull List<String> command) {
    if (command.size() < 3 || !"run".equals(command.get(1))) return false;
    String tool = command.get(2);
    return tool.equals("lime") || tool.equals("openfl");
  }

  /** One of the tool's actions as a full command line, using the file's selected target (all its flags). */
  @NotNull
  public static List<String> actionCommand(@NotNull Project project,
                                           @Nullable String environmentSdk,
                                           @NotNull VirtualFile file,
                                           @NotNull HaxeBuildFileType type,
                                           @NotNull String actionName) {
    List<String> command = new ArrayList<>(List.of(HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk),
                                                   "run", toolFor(type), actionName, file.getName()));
    List<String> targetFlags = selectedTargetFlags(project, type, file);
    command.addAll(targetFlags);
    command.addAll(airSdkDefine(project, targetFlags));
    return command;
  }

  /**
   * The air target's packaging and launch tools live in the AIR SDK, which
   * the lime tool locates through the AIR_SDK define - without it every air
   * action fails with "You must define AIR_SDK". The configured Flex/AIR SDK
   * entry's home supplies it; a CLI define lands after the project xml's, so
   * the IDE's SDK selection wins over a value hardcoded there. Attached -D
   * spelling: the two-word form trips a lime bug duplicating the value.
   */
  @NotNull
  private static List<String> airSdkDefine(@NotNull Project project, @NotNull List<String> targetFlags) {
    if (!targetFlags.contains("air")) return List.of();
    String flexSdkName = HaxeToolPathResolver.resolveFlexSdkName(project, null);
    Sdk sdk = flexSdkName == null ? null : ProjectJdkTable.getInstance().findJdk(flexSdkName);
    if (sdk == null || sdk.getHomePath() == null) return List.of();
    return List.of("-DAIR_SDK=" + sdk.getHomePath());
  }

  /**
   * Environment additions a resolved tool command needs when spawned: lime
   * 8.3.2's air packaging GUARDS on the AIR_SDK define but READS the value
   * from project.environment - only a real environment variable lands in
   * both maps, so the CLI define alone crashes the packaging step on a null
   * path. The injected define (see {@link #airSdkDefine}) is mirrored into
   * the process environment; commands without it get no additions.
   */
  @NotNull
  public static Map<String, String> commandEnvironment(@NotNull List<String> command) {
    for (String argument : command) {
      if (argument.startsWith("-DAIR_SDK=")) {
        return Map.of("AIR_SDK", argument.substring("-DAIR_SDK=".length()));
      }
    }
    return Map.of();
  }

  /** The target flags whose packaged app is a host-launchable binary (see {@link #packagedBinary}). */
  public static final Set<String> HOST_LAUNCHABLE_TARGETS = Set.of("neko", "hl", "cpp", "windows", "linux", "mac");

  /** The target flags whose packaged artifact is a swf, hosted under adl for test runs. */
  public static final Set<String> FLASH_FAMILY_TARGETS = Set.of("flash", "air");

  /** The packaged swf a flash/air build exports ({@code <app path>/<target>/bin/<app file>.swf}), or null without an app file. */
  @Nullable
  public static Path packagedSwf(@NotNull VirtualFile projectFile, @NotNull String content, @NotNull String targetFlag) {
    if (!FLASH_FAMILY_TARGETS.contains(targetFlag)) return null;
    String appFile = ProjectXmlParser.parseAppFile(content);
    String appPath = StringUtil.defaultIfEmpty(ProjectXmlParser.parseAppPath(content), "bin");
    if (appFile == null) return null;
    return Path.of(projectFile.getParent().getPath())
      .resolve(appPath)
      .resolve(targetFlag)
      .resolve("bin")
      .resolve(appFile + ".swf")
      .normalize();
  }

  /** The haxe compilation target behind a lime CLI target id, or null for an unknown id. */
  @Nullable
  public static HaxeTarget targetFor(@NotNull String targetFlag) {
    return switch (targetFlag) {
      case "hl" -> HaxeTarget.HL;
      case "html5" -> HaxeTarget.JAVA_SCRIPT;
      case "flash", "air" -> HaxeTarget.FLASH;
      case "neko" -> HaxeTarget.NEKO;
      case "java" -> HaxeTarget.JAVA;
      case "cppia" -> HaxeTarget.CPPIA;
      case "cs" -> HaxeTarget.CSHARP;
      case "cpp", "windows", "mac", "linux", "android", "ios" -> HaxeTarget.CPP;
      default -> null;
    };
  }

  /**
   * The launchable binary a lime build packages for a host target:
   * {@code <app path>/<target dir>/bin/<app file>[.exe]}, relative to the
   * project file. Neko output is wrapped in a launcher executable and an HL
   * build ships a renamed copy of the hl runtime beside its hlboot.dat — for
   * all host targets the packaged binary itself is what runs. Null when the
   * project xml declares no app file/path or the target is not host-launchable.
   */
  @Nullable
  public static Path packagedBinary(@NotNull VirtualFile projectFile, @NotNull String content, @NotNull String targetFlag) {
    if (!HOST_LAUNCHABLE_TARGETS.contains(targetFlag)) return null;
    String appFile = ProjectXmlParser.parseAppFile(content);
    String appPath = StringUtil.defaultIfEmpty(ProjectXmlParser.parseAppPath(content), "bin");
    if (appFile == null) return null;
    return Path.of(projectFile.getParent().getPath())
      .resolve(appPath)
      .resolve(targetDirectory(targetFlag))
      .resolve("bin")
      .resolve(HaxeSdkUtilBase.getExecutableName(appFile))
      .normalize();
  }

  /** The export subdirectory a target builds into; "cpp" is the tool's alias for the host platform. */
  @NotNull
  private static String targetDirectory(@NotNull String targetFlag) {
    if (!targetFlag.equals("cpp")) return targetFlag;
    if (SystemInfo.isWindows) return "windows";
    return SystemInfo.isMac ? "mac" : "linux";
  }
}
