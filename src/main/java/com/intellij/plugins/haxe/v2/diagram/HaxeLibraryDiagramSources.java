package com.intellij.plugins.haxe.v2.diagram;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HxmlIncludeTree;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.HaxeKnownBuildFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** What the diagram reads about a build file: its include tree, or for a non-hxml build file one entry with its inspected declarations. */
final class HaxeLibraryDiagramSources {

  private static final String HXML_EXTENSION = "hxml";

  private HaxeLibraryDiagramSources() {
  }

  /** Null when the path is not a file any more. Read action. */
  @Nullable
  static HxmlIncludeTree treeOf(@NotNull Project project, @NotNull String path) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null) return null;
    if (HXML_EXTENSION.equalsIgnoreCase(file.getExtension())) return HxmlIncludeTree.of(file);
    HaxeBuildFile buildFile = knownBuildFile(project, path);
    HaxeBuildFileInfo info = buildFile == null ? HaxeBuildFileInfo.EMPTY : HaxeBuildSections.inspectSelected(project, buildFile);
    return HxmlIncludeTree.leaf(file, info);
  }

  @Nullable
  private static HaxeBuildFile knownBuildFile(@NotNull Project project, @NotNull String path) {
    for (HaxeBuildFile buildFile : HaxeKnownBuildFiles.all(project)) {
      if (path.equals(buildFile.file().getPath())) return buildFile;
    }
    return null;
  }
}
