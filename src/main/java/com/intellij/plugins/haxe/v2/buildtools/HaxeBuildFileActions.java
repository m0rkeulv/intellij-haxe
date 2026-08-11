package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// The per-type dispatch for build-file actions: which built-in actions a
/// [HaxeBuildFileType] offers, the command a named built-in action runs, and
/// the file's selected target flag. One exhaustive switch per concern - a new
/// build-file type extends this class only, not every caller.
/// TODO: fold these methods into HaxeBuildFileType once it moves out of
///  `v2/toolwindow` - on the enum today they would cycle toolwindow <-> buildtools.
public final class HaxeBuildFileActions {

  private HaxeBuildFileActions() {
  }

  /** The type's built-in action names, in menu order (custom actions come on top of these). */
  @NotNull
  public static List<String> defaultActionNames(@NotNull HaxeBuildFileType type) {
    return switch (type) {
      case HXML -> List.of(HxmlProjects.BUILD_ACTION);
      case OPENFL, LIME, HXP_PROJECT -> LimeProjects.DEFAULT_ACTIONS;
      case NMML -> NmeProjects.DEFAULT_ACTIONS;
      case HXP_SCRIPT -> List.of(HxpScriptProjects.BUILD_ACTION);
    };
  }

  /** The command compiling the file with the type's default build action. */
  @NotNull
  public static List<String> defaultCommand(@NotNull Project project,
                                            @Nullable String environmentSdk,
                                            @NotNull VirtualFile file,
                                            @NotNull HaxeBuildFileType type) {
    return switch (type) {
      case HXML -> HxmlProjects.buildCommand(project, environmentSdk, file);
      case OPENFL, LIME, HXP_PROJECT -> LimeProjects.actionCommand(project, environmentSdk, file, type, LimeProjects.BUILD_ACTION);
      case NMML -> NmeProjects.actionCommand(project, environmentSdk, file, NmeProjects.BUILD_ACTION);
      case HXP_SCRIPT -> HxpScriptProjects.buildCommand(project, environmentSdk, file);
    };
  }

  /** A named built-in action's command; null when the name is not one of the type's built-ins. */
  @Nullable
  public static List<String> defaultActionCommand(@NotNull Project project,
                                                  @Nullable String environmentSdk,
                                                  @NotNull VirtualFile file,
                                                  @NotNull HaxeBuildFileType type,
                                                  @NotNull String actionName) {
    return switch (type) {

      case HXML -> HxmlProjects.isBuildAction(actionName)
                   ? HxmlProjects.buildCommand(project, environmentSdk, file)
                   : null;

      case OPENFL, LIME, HXP_PROJECT -> LimeProjects.DEFAULT_ACTIONS.contains(actionName)
                                        ? LimeProjects.actionCommand(project, environmentSdk, file, type, actionName)
                                        : null;

      case NMML -> NmeProjects.DEFAULT_ACTIONS.contains(actionName)
                   ? NmeProjects.actionCommand(project, environmentSdk, file, actionName)
                   : null;

      case HXP_SCRIPT -> HxpScriptProjects.BUILD_ACTION.equals(actionName)
                         ? HxpScriptProjects.buildCommand(project, environmentSdk, file)
                         : null;
    };
  }

  /**
   * The file's currently selected target flag (e.g. "windows", "html5"); null
   * for types without a selectable target (hxml declares its own, a plain hxp
   * script decides in code).
   */
  @Nullable
  public static String selectedTargetFlag(@NotNull Project project,
                                          @NotNull VirtualFile file,
                                          @NotNull HaxeBuildFileType type) {
    return switch (type) {
      case OPENFL, LIME, HXP_PROJECT -> LimeProjects.selectedTargetFlag(project, type, file);
      case NMML -> NmeProjects.selectedTargetFlag(project, file);
      case HXML, HXP_SCRIPT -> null;
    };
  }
}
