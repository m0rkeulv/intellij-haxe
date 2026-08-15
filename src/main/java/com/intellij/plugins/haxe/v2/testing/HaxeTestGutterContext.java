package com.intellij.plugins.haxe.v2.testing;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.plugins.haxe.buildsystem.hxml.HXMLFileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildClasspaths;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeTestsBuildFileStore;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which tests build owns a source file, for the gutter run markers: the first
 * marked-or-conventional tests build file whose classpaths contain the file
 * (see {@link #candidateTestsPaths}), carrying the framework that build
 * declares. A file no tests build claims gets no markers - without the owning
 * build there are no classpaths, defines or libraries to compile a single
 * test with. Resolution touches only PSI/VFS and the file-type index (marker
 * passes run under the read lock), cached per file against PSI and marked-set
 * changes.
 */
public final class HaxeTestGutterContext {

  /** The owning tests build and its framework - everything a gutter run needs. */
  public record TestContext(@NotNull HaxeTestFramework framework, @NotNull String testsBuildPath) {
  }

  private HaxeTestGutterContext() {
  }

  /** The file's gutter context, or null when no marked tests build owns it (or its framework has no gutter support). */
  @Nullable
  public static TestContext contextFor(@NotNull PsiFile file) {
    return CachedValuesManager.getCachedValue(file, () -> {
      HaxeTestsBuildFileStore store = HaxeTestsBuildFileStore.getInstance(file.getProject());
      return CachedValueProvider.Result.create(
        compute(file, store), PsiModificationTracker.MODIFICATION_COUNT, store.getModificationTracker());
    });
  }

  @Nullable
  private static TestContext compute(@NotNull PsiFile file, @NotNull HaxeTestsBuildFileStore store) {
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    Project project = file.getProject();

    for (String testsPath : candidateTestsPaths(project, store)) {
      VirtualFile testsFile = LocalFileSystem.getInstance().findFileByPath(testsPath);
      if (testsFile == null || !testsFile.isValid()) continue;
      // TODO gutter runs for lime/nme tests builds: their inspection shells
      //  out to the tool (cache-only + background hydration needed) and the
      //  template compile needs the effective hxml as a plain-haxe build
      if (HaxeBuildFileScanner.detectType(project, testsFile) != HaxeBuildFileType.HXML) continue;

      List<String> sourceDirectories = HaxeBuildClasspaths.sourceDirectories(project, testsPath);
      boolean owns = sourceDirectories.stream()
        .anyMatch(directory -> virtualFile.getPath().startsWith(directory + "/"));
      if (!owns) continue;

      HaxeTestFramework framework = HaxeTestFrameworks.forBuildFile(project, testsPath);
      if (framework.singleRunTemplate(false) == null) continue;
      return new TestContext(framework, testsPath);
    }
    return null;
  }

  /**
   * The tests builds a file can belong to: the explicitly MARKED ones first,
   * then the CONVENTIONAL candidates among the project's hxml files
   * (test.hxml/tests.hxml names, files under a tests/ directory) minus the
   * explicitly EXCLUDED ones — the same per-file toggle rule the tool
   * window's Tests rows follow, so a project that never explicitly marked
   * anything still gets markers.
   */
  @NotNull
  private static List<String> candidateTestsPaths(@NotNull Project project, @NotNull HaxeTestsBuildFileStore store) {
    List<String> candidates = new ArrayList<>(store.getAllTestsFilePaths());
    List<String> excluded = store.getAllExcludedFilePaths();
    for (VirtualFile hxml : FileTypeIndex.getFiles(HXMLFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
      String path = hxml.getPath();
      boolean conventional = HaxeTestsBuildFileStore.isConventionalTestsPath(path) && !excluded.contains(path);
      if (conventional && !candidates.contains(path)) {
        candidates.add(path);
      }
    }
    return candidates;
  }
}
