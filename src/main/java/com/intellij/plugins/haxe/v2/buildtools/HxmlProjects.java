package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Facts about plain hxml builds: the direct {@code haxe <file>} compile and
 * its single default action. The lime counterpart is {@link LimeProjects}.
 */
public final class HxmlProjects {

  /** The hxml default action's name - doubles as its identifier in stored configurations. */
  public static final String BUILD_ACTION = "Build";

  private HxmlProjects() {
  }

  /** Whether a stored action name means the hxml build ("compile" is the action's pre-rename identifier). */
  public static boolean isBuildAction(@NotNull String actionName) {
    return actionName.equals(BUILD_ACTION) || actionName.equals("compile");
  }

  /** The direct compile: {@code haxe <file>}, run in the file's directory. */
  @NotNull
  public static List<String> buildCommand(@NotNull Project project,
                                          @Nullable String environmentSdk,
                                          @NotNull VirtualFile file) {
    return List.of(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk), file.getName());
  }

  /** Whether the command invokes the haxe compiler directly (and so takes {@code --connect} right after the executable). */
  public static boolean isDirectHaxeCommand(@NotNull Project project,
                                            @Nullable String environmentSdk,
                                            @NotNull List<String> command) {
    return command.get(0).equals(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk));
  }
}
