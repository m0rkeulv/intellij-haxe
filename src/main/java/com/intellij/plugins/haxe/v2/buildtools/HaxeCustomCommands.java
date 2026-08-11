package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * User-typed custom action commands: variable expansion and parsing into a
 * runnable argv.
 */
public final class HaxeCustomCommands {

  private HaxeCustomCommands() {
  }

  /**
   * Expands {@code ${target}} to the file's currently selected target flag
   * (e.g. "windows", "html5"), so one action follows the target switcher the
   * way the default actions do. Types without a selectable target (hxml
   * declares its own, a plain hxp script decides in code) keep the literal,
   * making the unapplied variable visible instead of silently vanishing.
   */
  @NotNull
  public static String expandVariables(@NotNull Project project,
                                       @NotNull VirtualFile file,
                                       @NotNull HaxeBuildFileType type,
                                       @NotNull String command) {
    if (!command.contains("${target}")) return command;
    String targetFlag = HaxeBuildFileActions.selectedTargetFlag(project, file, type);
    return targetFlag == null ? command : command.replace("${target}", targetFlag);
  }

  /**
   * Parses a user-typed command. On Windows a bare program name is resolved
   * through PATH honoring PATHEXT: a terminal launches "nme" (nme.bat) fine,
   * but CreateProcess never tries extensions and fails with error=2, so the
   * parsed command carries the resolved absolute path instead.
   */
  @NotNull
  public static List<String> parse(@NotNull String command) {
    List<String> parsed = new ArrayList<>(ParametersListUtil.parse(command));
    if (!parsed.isEmpty()) {
      parsed.set(0, PathEnvironmentVariableUtil.findExecutableInWindowsPath(parsed.get(0)));
    }
    return parsed;
  }
}
