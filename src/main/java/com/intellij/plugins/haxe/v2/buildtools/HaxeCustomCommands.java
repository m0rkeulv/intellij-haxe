package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * User-typed custom action commands: variable expansion and parsing into a
 * runnable argv.
 */
public final class HaxeCustomCommands {

  /** The execution-time variable {@link #expandServerPort} resolves; resolve and display keep it literal. */
  public static final String SERVER_PORT_VARIABLE = "${serverPort}";

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
   * Expands {@code ${serverPort}} to the container's compilation-server port,
   * starting the server when needed. The port is ephemeral (a restart picks a
   * new one), so it can only resolve at EXECUTION time — never during resolve
   * or tree display, which run under read actions and must not launch
   * processes. With the server feature disabled, or the server failing to
   * start, the literal stays visible instead of silently vanishing.
   */
  @NotNull
  public static List<String> expandServerPort(@NotNull Project project,
                                              @NotNull String containerId,
                                              @NotNull List<String> command) {
    boolean wantsPort = command.stream().anyMatch(argument -> argument.contains(SERVER_PORT_VARIABLE));
    if (!wantsPort) return command;
    if (!HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled()) return command;

    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    int port = HaxeCompilationServerManager.getInstance(project).ensureRunning(environmentSdk);
    if (port <= 0) return command;
    return command.stream()
      .map(argument -> argument.replace(SERVER_PORT_VARIABLE, String.valueOf(port)))
      .toList();
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
