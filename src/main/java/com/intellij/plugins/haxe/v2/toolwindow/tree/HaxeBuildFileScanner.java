package com.intellij.plugins.haxe.v2.toolwindow.tree;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.ide.projectStructure.detection.HaxeProjectFileDetectionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds Haxe build/project files (hxml, OpenFL/Lime xml, nmml, hxp). Only the top
 * level of each content root is scanned: generated build files in subfolders (for
 * example OpenFL's Export directory) must not be picked up - files in subfolders
 * are registered manually via Add Build File. Must run in a read action.
 */
public final class HaxeBuildFileScanner {

  private HaxeBuildFileScanner() {
  }

  @NotNull
  public static List<HaxeBuildFile> scan(@NotNull Module module) {
    List<HaxeBuildFile> result = new ArrayList<>();
    for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
      scanDirectory(contentRoot, result);
    }
    sortByName(result);
    return result;
  }

  /**
   * Build files directly in the project base dir that belong to no module (such files
   * are outside every content root, so the module scan cannot see them).
   */
  @NotNull
  public static List<HaxeBuildFile> scanProjectRoot(@NotNull Project project) {
    VirtualFile baseDir = ProjectUtil.guessProjectDir(project);
    if (baseDir == null) return List.of();

    List<HaxeBuildFile> result = new ArrayList<>();
    for (VirtualFile child : baseDir.getChildren()) {
      if (child.isDirectory() || ModuleUtilCore.findModuleForFile(child, project) != null) continue;
      addIfBuildFile(child, result);
    }
    sortByName(result);
    return result;
  }

  private static void scanDirectory(@NotNull VirtualFile directory, @NotNull List<HaxeBuildFile> result) {
    for (VirtualFile child : directory.getChildren()) {
      if (!child.isDirectory()) {
        addIfBuildFile(child, result);
      }
    }
  }

  private static void addIfBuildFile(@NotNull VirtualFile file, @NotNull List<HaxeBuildFile> result) {
    HaxeBuildFileType type = detectType(file);
    if (type != null) {
      result.add(new HaxeBuildFile(file, type));
    }
  }

  /** Detects the build file type of an arbitrary file (any xml name is accepted). */
  @Nullable
  public static HaxeBuildFileType detectType(@NotNull VirtualFile file) {
    String extension = file.getExtension();
    if (extension == null) return null;
    return switch (extension.toLowerCase(Locale.ROOT)) {
      case "hxml" -> HaxeBuildFileType.HXML;
      case "hxp" -> HaxeBuildFileType.HXP;
      case "nmml" -> HaxeProjectFileDetectionUtil.isNMMLProject(file) ? HaxeBuildFileType.NMML : null;
      case "xml" -> {
        if (HaxeProjectFileDetectionUtil.isOpenFLProject(file)) yield HaxeBuildFileType.OPENFL;
        if (HaxeProjectFileDetectionUtil.isLimeProject(file)) yield HaxeBuildFileType.LIME;
        yield null;
      }
      default -> null;
    };
  }

  private static void sortByName(@NotNull List<HaxeBuildFile> files) {
    files.sort(Comparator.comparing(buildFile -> buildFile.file().getName(), String.CASE_INSENSITIVE_ORDER));
  }
}
