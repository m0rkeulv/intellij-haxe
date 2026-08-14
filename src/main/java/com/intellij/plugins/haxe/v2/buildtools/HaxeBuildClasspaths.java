package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The directories a build's sources come from: the build file's own directory
 * plus the resolved {@code -cp} entries of its selected section (lime/nme
 * resolve through their effective hxml). Name-based lookups scope themselves
 * to these — test-result navigation and debugger source mapping would
 * otherwise pick a same-named file from a SIBLING project in the same IDE
 * project.
 */
public final class HaxeBuildClasspaths {

  private HaxeBuildClasspaths() {
  }

  /** Absolute directory paths (VFS separators), or empty when the build file cannot be inspected. Call in a read action. */
  @NotNull
  public static List<String> sourceDirectories(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile buildFile = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (buildFile == null || !buildFile.isValid() || buildFile.getParent() == null) return List.of();
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, buildFile);
    if (type == null) return List.of();

    VirtualFile parent = buildFile.getParent();
    List<String> directories = new ArrayList<>();
    directories.add(parent.getPath());
    List<String> classpaths =
      HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(buildFile, type)).classpaths();
    for (String classpath : classpaths) {
      String normalized = FileUtil.toSystemIndependentName(classpath.trim());
      VirtualFile resolved = FileUtil.isAbsolutePlatformIndependent(normalized)
                             ? LocalFileSystem.getInstance().findFileByPath(normalized)
                             : parent.findFileByRelativePath(normalized);
      if (resolved != null && resolved.isDirectory()) {
        directories.add(resolved.getPath());
      }
    }

    // -lib sources live outside the -cp entries; the sync'd managed module
    // libraries carry their resolved roots - include them so a file inside
    // library code stays in scope. The module's union of libraries
    // over-includes, which errs on the safe (fail-open) side.
    Module module = ModuleUtilCore.findModuleForFile(buildFile, project);
    if (module != null) {
      collectManagedLibraryRoots(module, directories);
    }
    return directories;
  }

  private static void collectManagedLibraryRoots(@NotNull Module module, @NotNull List<String> directories) {
    OrderEnumerator.orderEntries(module).forEachLibrary(library -> {
      String name = library.getName();
      if (name == null || !name.startsWith(HaxeLibrarySync.MANAGED_PREFIX)) {
        return true;
      }
      for (VirtualFile root : library.getFiles(OrderRootType.SOURCES)) {
        if (root.isDirectory()) {
          directories.add(root.getPath());
        }
      }
      return true;
    });
  }
}
