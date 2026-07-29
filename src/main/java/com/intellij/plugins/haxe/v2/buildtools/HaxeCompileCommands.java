package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeCustomActionsStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetOptions;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
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

  /** The hxml default action's name - doubles as its identifier in stored configurations. */
  public static final String HXML_BUILD_ACTION = "Build";

  private static final List<String> LIME_DEFAULT_ACTIONS = List.of("test", "run", "build", "clean");

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
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
    if (type == null) return null;

    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> base = null;
    if (stored.actionName() != null) {
      base = actionCommand(project, environmentSdk, file, type, stored.actionName());
    }
    if (base == null) {
      base = defaultCommand(project, environmentSdk, file, type);
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
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
    if (type == null) return null;

    String containerId = containerIdFor(project, file);
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<String> base = actionCommand(project, environmentSdk, file, type, actionName);
    if (base == null || base.isEmpty()) return null;
    return buildResolved(project, containerId, environmentSdk, file, base, extraArguments);
  }

  /** The container a build file belongs to: its module's name, or the project-root container. */
  @NotNull
  public static String containerIdFor(@NotNull Project project, @NotNull VirtualFile file) {
    Module module = ModuleUtilCore.findModuleForFile(file, project);
    return module != null ? module.getName() : HaxeToolWindowPanel.PROJECT_ROOT_CONTAINER;
  }

  /** Action names offered for a build file: the type's defaults plus its custom actions. */
  @NotNull
  public static List<String> availableActionNames(@NotNull Project project, @NotNull VirtualFile file) {
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
    List<String> names = new ArrayList<>();
    if (type == HaxeBuildFileType.HXML) {
      names.add(HXML_BUILD_ACTION);
    }
    else if (type != null && type != HaxeBuildFileType.NMML) {
      names.addAll(LIME_DEFAULT_ACTIONS);
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
    VirtualFile parent = file.getParent();
    String workDirectory = parent != null ? parent.getPath() : project.getBasePath();
    boolean connectEligible = isConnectEligible(project, environmentSdk, command);
    return new Resolved(containerId, command, workDirectory, String.join(" ", command), connectEligible);
  }

  /** True when the command can compile through the server: a direct haxe compile or a lime/openfl build. */
  public static boolean isConnectEligible(@NotNull Project project,
                                          @Nullable String environmentSdk,
                                          @NotNull List<String> command) {
    return isDirectHaxeCommand(project, environmentSdk, command) || isLimeToolCommand(command);
  }

  private static boolean isDirectHaxeCommand(@NotNull Project project,
                                             @Nullable String environmentSdk,
                                             @NotNull List<String> command) {
    return command.get(0).equals(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk));
  }

  /**
   * A {@code haxelib run lime|openfl …} invocation. The lime tool forwards a
   * trailing {@code --connect <port>} pair into the haxe builds it generates
   * (CommandLineTools.hx treats --connect as a haxeflag whose next argument is
   * captured with it), so these commands can use the compilation server too.
   */
  private static boolean isLimeToolCommand(@NotNull List<String> command) {
    if (command.size() < 3 || !"run".equals(command.get(1))) return false;
    String tool = command.get(2);
    return tool.equals("lime") || tool.equals("openfl");
  }

  /**
   * Adds {@code --connect <port>} when the compilation server is enabled and the
   * container participates: right after the executable for a direct haxe compile,
   * appended for a lime/openfl build (the tool forwards it to its haxe calls);
   * otherwise returns the command unchanged.
   */
  @NotNull
  public static List<String> connectIfEnabled(@NotNull Project project,
                                              @NotNull String containerId,
                                              boolean connectEligible,
                                              @NotNull List<String> command) {
    boolean useServer = connectEligible
                        && HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled()
                        && HaxeEnvironmentStore.getInstance(project).isUsingCompilationServer(containerId);
    if (!useServer) return command;

    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    int port = HaxeCompilationServerManager.getInstance(project).ensureRunning(environmentSdk);
    if (port <= 0) return command;

    List<String> connected = new ArrayList<>(command);
    List<String> connectArguments = List.of("--connect", String.valueOf(port));
    if (isDirectHaxeCommand(project, environmentSdk, command)) {
      connected.addAll(1, connectArguments);
    }
    else {
      connected.addAll(connectArguments);
    }
    return connected;
  }

  @Nullable
  private static List<String> defaultCommand(@NotNull Project project,
                                             @Nullable String environmentSdk,
                                             @NotNull VirtualFile file,
                                             @NotNull HaxeBuildFileType type) {
    return switch (type) {
      case HXML -> List.of(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk), file.getName());
      case OPENFL, LIME, HXP -> limeCommand(project, environmentSdk, file, type, "build");
      case NMML -> null;
    };
  }

  /** A named default action's command, or a custom action's; null when unknown. */
  @Nullable
  private static List<String> actionCommand(@NotNull Project project,
                                            @Nullable String environmentSdk,
                                            @NotNull VirtualFile file,
                                            @NotNull HaxeBuildFileType type,
                                            @NotNull String actionName) {
    // "compile" is the action's pre-rename identifier - stored configurations still carry it
    boolean hxmlBuild = actionName.equals(HXML_BUILD_ACTION) || actionName.equals("compile");
    if (type == HaxeBuildFileType.HXML && hxmlBuild) {
      return List.of(HaxeToolPathResolver.resolveHaxeExecutable(project, environmentSdk), file.getName());
    }
    if (type != HaxeBuildFileType.HXML && type != HaxeBuildFileType.NMML && LIME_DEFAULT_ACTIONS.contains(actionName)) {
      return limeCommand(project, environmentSdk, file, type, actionName);
    }
    return HaxeCustomActionsStore.getInstance(project).getActions(file.getPath()).stream()
      .filter(custom -> custom.name().equals(actionName))
      .findFirst()
      .map(custom -> ParametersListUtil.parse(custom.command()))
      .orElse(null);
  }

  @NotNull
  private static List<String> limeCommand(@NotNull Project project,
                                          @Nullable String environmentSdk,
                                          @NotNull VirtualFile file,
                                          @NotNull HaxeBuildFileType type,
                                          @NotNull String actionName) {
    String tool = type == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
    String targetFlag = HaxeTargetOptions.targetFlagFor(
      type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file));
    return List.of(HaxeToolPathResolver.resolveHaxelibExecutable(project, environmentSdk),
                   "run", tool, actionName, file.getName(), targetFlag);
  }
}
