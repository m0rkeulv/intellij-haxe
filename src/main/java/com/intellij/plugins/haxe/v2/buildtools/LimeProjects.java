package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
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
    command.addAll(selectedTargetFlags(project, type, file));
    return command;
  }

  /** The target flags whose packaged app is a host-launchable binary (see {@link #packagedBinary}). */
  public static final Set<String> HOST_LAUNCHABLE_TARGETS = Set.of("neko", "hl", "cpp", "windows", "linux", "mac");

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
