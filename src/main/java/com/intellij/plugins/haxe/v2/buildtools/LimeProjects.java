package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetOptions;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
}
