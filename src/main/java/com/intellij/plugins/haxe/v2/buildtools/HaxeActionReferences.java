package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeCustomActionsStore.ActionReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// How a run-before reference reads: an action of the referring action's own
/// build file by its name alone, any other by its module and the build file's
/// path inside that module, so two modules' `build.hxml` stay apart -
/// `moduleB › build.hxml › Build`.
public final class HaxeActionReferences {

  private HaxeActionReferences() {
  }

  /** The reference as a row or a list shows it, read from an action of {@code ownerId}. */
  @NotNull
  public static String label(@NotNull Project project, @NotNull String ownerId, @NotNull ActionReference reference) {
    String fileLabel = fileLabel(project, ownerId, reference);
    return fileLabel == null ? reference.action() : HaxeBundle.message("haxe.action.reference", fileLabel, reference.action());
  }

  /** The build file part of the label - module and path inside it - or null for {@code ownerId}'s own file. */
  @Nullable
  public static String fileLabel(@NotNull Project project, @NotNull String ownerId, @NotNull ActionReference reference) {
    String buildFile = reference.buildFile();
    if (buildFile == null || buildFile.equals(ownerId)) return null;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFile);
    // a deleted file: its path is all there is to show
    if (file == null) return buildFile;
    return HaxeReadActions.compute(() -> moduleAndPath(project, file));
  }

  /** The file's module, or the project for the project root, and its path inside it. */
  @NotNull
  private static String moduleAndPath(@NotNull Project project, @NotNull VirtualFile file) {
    String containerId = HaxeContainers.containerIdFor(project, file);
    String container = HaxeContainers.isProjectRoot(containerId) ? project.getName() : containerId;
    return HaxeBundle.message("haxe.action.reference.file", container, pathInside(project, containerId, file));
  }

  /** The file's path relative to its container's root, or its name when it lies elsewhere. */
  @NotNull
  private static String pathInside(@NotNull Project project, @NotNull String containerId, @NotNull VirtualFile file) {
    String root = HaxeContainers.containerRootPath(project, containerId);
    String path = file.getPath();
    if (root == null || !path.startsWith(root + "/")) return file.getName();
    return path.substring(root.length() + 1);
  }
}
