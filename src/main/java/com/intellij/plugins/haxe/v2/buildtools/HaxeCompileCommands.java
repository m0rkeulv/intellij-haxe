package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.plugins.haxe.v2.buildtools.server.HaxeCompilationServerManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a container's configured build command into a runnable command line,
 * independent of the tool window. Used by the project task runner (IDE Build) and
 * shared connect-injection for the compilation server.
 */
public final class HaxeCompileCommands {

  public record Resolved(@NotNull String containerId,
                         @NotNull List<String> command,
                         @Nullable String workDirectory,
                         @NotNull String presentable,
                         boolean connectEligible) {
  }

  private HaxeCompileCommands() {
  }

  /** Resolves the container's build command; null when unset, the file is gone or the type has no build support. Call in a read action. */
  @Nullable
  public static Resolved resolve(@NotNull Project project, @NotNull String containerId) {
    HaxeEnvironmentStore.CompileCommand stored = HaxeEnvironmentStore.getInstance(project).getCompileCommand(containerId);
    if (stored == null) return null;

    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(stored.buildFilePath());
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return null;

    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> base = null;
    if (stored.actionName() != null) {
      base = actionCommand(project, environmentSdk, file, type, stored.actionName());
    }
    if (base == null) {
      base = HaxeBuildFileActions.defaultCommand(project, environmentSdk, file, type);
    }
    if (base == null || base.isEmpty()) return null;

    return buildResolved(project, containerId, environmentSdk, file, base, stored.arguments());
  }

  /**
   * Resolves a specific action of a build file into a runnable command line (the
   * run configuration path - the container is derived from the file's module).
   * Call in a read action.
   */
  @Nullable
  public static Resolved resolveAction(@NotNull Project project,
                                       @NotNull String buildFilePath,
                                       @NotNull String actionName,
                                       @NotNull String extraArguments) {

    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return null;

    String containerId = HaxeContainers.containerIdFor(project, file);
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> base = actionCommand(project, environmentSdk, file, type, actionName);
    if (base == null || base.isEmpty()) return null;
    return buildResolved(project, containerId, environmentSdk, file, base, extraArguments);
  }

  /** Action names offered for a build file: the type's defaults plus its custom actions. */
  @NotNull
  public static List<String> availableActionNames(@NotNull Project project, @NotNull VirtualFile file) {
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    List<String> names = new ArrayList<>();
    if (type != null) {
      names.addAll(HaxeBuildFileActions.defaultActionNames(type));
    }
    for (HaxeCustomActionsStore.CustomAction custom : HaxeCustomActionsStore.getInstance(project).getActions(file.getPath())) {
      if (!names.contains(custom.name())) {
        names.add(custom.name());
      }
    }
    return names;
  }

  @NotNull
  private static Resolved buildResolved(@NotNull Project project,
                                        @NotNull String containerId,
                                        @Nullable String environmentSdk,
                                        @NotNull VirtualFile file,
                                        @NotNull List<String> base,
                                        @NotNull String extraArguments) {
    List<String> command = new ArrayList<>(base);
    command.addAll(ParametersListUtil.parse(extraArguments));
    String workDirectory = HaxeBuildWorkDirectories.workDirectory(project, file);
    boolean eligible = connectEligible(project, environmentSdk, command, file);
    return new Resolved(containerId, command, workDirectory, String.join(" ", command), eligible);
  }

  /** True when the build file's command may ride the compilation server: connect-capable and not emitting a swf. */
  public static boolean connectEligible(@NotNull Project project,
                                        @Nullable String environmentSdk,
                                        @NotNull List<String> command,
                                        @NotNull VirtualFile file) {
    return isConnectEligible(project, environmentSdk, command) && !producesSwf(project, file);
  }

  /**
   * Whether the build emits a swf. The compilation server produces a corrupt
   * swf on the second compile of the same build (VerifyError #1053 at load),
   * so swf-emitting compiles never ride {@code --connect}. Display/diagnostic
   * requests are unaffected - the corruption is in swf generation only.
   */
  private static boolean producesSwf(@NotNull Project project, @NotNull VirtualFile file) {
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return false;
    HaxeTarget target = HaxeBuildSystem.of(type).launchTarget(project, new HaxeBuildFile(file, type));
    return target == HaxeTarget.FLASH;
  }

  /** True when the command can compile through the server: a direct haxe compile or a lime/openfl/nme build. */
  private static boolean isConnectEligible(@NotNull Project project,
                                           @Nullable String environmentSdk,
                                           @NotNull List<String> command) {
    return HxmlProjects.isDirectHaxeCommand(project, environmentSdk, command)
           || LimeProjects.isToolCommand(command)
           || NmeProjects.isToolCommand(command);
  }

  /// The shared execution-time server step: expands a custom action's
  /// `${serverPort}` variable, then adds `--connect <port>` when the
  /// compilation server is enabled and the container participates - right
  /// after the executable for a direct haxe compile, appended for a
  /// lime/openfl/nme build (the tools forward it to their haxe calls);
  /// otherwise returns the command unchanged.
  @NotNull
  public static List<String> connectIfEnabled(@NotNull Project project,
                                              @NotNull String containerId,
                                              boolean connectEligible,
                                              @NotNull List<String> rawCommand) {
    List<String> command = HaxeCustomCommands.expandServerPort(project, containerId, rawCommand);
    boolean useServer = connectEligible
                        && HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled()
                        && HaxeEnvironmentStore.getInstance(project).isUsingCompilationServer(containerId);
    if (!useServer) return command;

    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    int port = HaxeCompilationServerManager.getInstance(project).ensureRunning(environmentSdk);
    if (port <= 0) return command;

    List<String> connected = new ArrayList<>(command);
    List<String> connectArguments = List.of("--connect", String.valueOf(port));
    if (HxmlProjects.isDirectHaxeCommand(project, environmentSdk, command)) {
      connected.addAll(1, connectArguments);
    }
    else if (NmeProjects.isToolCommand(command)) {
      // The nme tool pushes a standalone "--connect" into its haxeflags twice
      // (all versions), so haxe reads the duplicate as the port and fails with
      // "Invalid port". A single "--connect <port>" token dodges the double
      // push and becomes one line of the generated build.hxml, which haxe
      // parses as flag + value.
      connected.add("--connect " + port);
    }
    else {
      connected.addAll(connectArguments);
    }
    return connected;
  }

  /** A named default action's command, or a custom action's; null when unknown. */
  @Nullable
  private static List<String> actionCommand(@NotNull Project project,
                                            @Nullable String environmentSdk,
                                            @NotNull VirtualFile file,
                                            @NotNull HaxeBuildFileType type,
                                            @NotNull String actionName) {
    List<String> defaultAction = HaxeBuildFileActions.defaultActionCommand(project, environmentSdk, file, type, actionName);
    if (defaultAction != null) return defaultAction;

    return HaxeCustomActionsStore.getInstance(project).getActions(file.getPath()).stream()
      .filter(custom -> custom.name().equals(actionName))
      .findFirst()
      .map(custom -> HaxeCustomCommands.expandVariables(project, file, type, custom.command()))
      .map(HaxeCustomCommands::parse)
      .orElse(null);
  }
}
