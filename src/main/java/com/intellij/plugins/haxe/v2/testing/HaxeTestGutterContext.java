package com.intellij.plugins.haxe.v2.testing;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.plugins.haxe.buildsystem.hxml.HXMLFileType;
import com.intellij.plugins.haxe.buildsystem.hxml.HXMLLanguage;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildClasspaths;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
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
 * passes run under the read lock), cached per file against the marked set,
 * the VFS structure and build-file content (see {@link #dependencies}).
 */
public final class HaxeTestGutterContext {

  /** The owning tests build and its framework - everything a gutter run needs. */
  public record TestContext(@NotNull HaxeTestFramework framework, @NotNull String testsBuildPath) {
  }

  private HaxeTestGutterContext() {
  }

  /** The file's gutter context, or null when no marked-or-conventional tests build owns it (or its framework has no gutter support). */
  @Nullable
  public static TestContext contextFor(@NotNull PsiFile file) {
    return CachedValuesManager.getCachedValue(file, () ->
      CachedValueProvider.Result.create(compute(file), dependencies(file.getProject())));
  }

  /**
   * What the answer is built from: the marked/excluded sets, the project's
   * file SET (candidate discovery and path-based ownership) and the CONTENT
   * of hxml/xml build files (classpaths, -lib declarations) - deliberately
   * not the whole-PSI counter, which bumps on every keystroke in every file
   * and would rerun the candidate scan per edit.
   */
  private static Object @NotNull [] dependencies(@NotNull Project project) {
    PsiModificationTracker psiTracker = PsiModificationTracker.getInstance(project);
    return new Object[]{
      HaxeTestsBuildFileStore.getInstance(project).getModificationTracker(),
      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
      psiTracker.forLanguage(HXMLLanguage.INSTANCE),
      psiTracker.forLanguage(XMLLanguage.INSTANCE)};
  }

  @Nullable
  private static TestContext compute(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    Project project = file.getProject();

    for (String testsPath : candidateTestsPaths(project)) {
      VirtualFile testsFile = LocalFileSystem.getInstance().findFileByPath(testsPath);
      if (testsFile == null || !testsFile.isValid()) continue;
      // lime-family ownership/detection reads the project xml's DECLARED
      // sources and haxelibs (no tool run) - precise enough to claim a file
      // TODO gutter runs for nmml tests builds: nme's display mode is unverified
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, testsFile);
      boolean supported = type == HaxeBuildFileType.HXML || LimeProjects.isLimeFamily(type);
      if (!supported) continue;

      // ONE inspection per candidate: its classpaths decide ownership, its
      // -lib declarations the framework
      HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(testsFile, type));
      List<String> sourceDirectories = HaxeBuildClasspaths.sourceDirectories(project, testsFile, info.classpaths());
      boolean owns = sourceDirectories.stream()
        .anyMatch(directory -> virtualFile.getPath().startsWith(directory + "/"));
      if (!owns) continue;

      // a build declaring no framework lib offers no markers - the detection
      // default must not turn an application build into a utest run
      HaxeTestFramework framework = HaxeTestFrameworks.detectedFramework(info.libraries());
      if (framework == null || framework.singleRunTemplate(false) == null) continue;
      return new TestContext(framework, testsPath);
    }
    return null;
  }

  /**
   * The tests builds a file can belong to: the explicitly MARKED ones first,
   * then the CONVENTIONAL candidates among the project's hxml and
   * lime-family project files (test.hxml/tests.hxml names, files under a
   * tests/ directory) minus the explicitly EXCLUDED ones — the same per-file
   * toggle rule the tool window's Tests rows follow, so a project that never
   * explicitly marked anything still gets markers. Cached at project level:
   * the discovery enumerates every hxml and xml file in project scope, far
   * too much per gutter file.
   */
  @NotNull
  private static List<String> candidateTestsPaths(@NotNull Project project) {
    return CachedValuesManager.getManager(project).getCachedValue(project, () ->
      CachedValueProvider.Result.create(computeCandidateTestsPaths(project), dependencies(project)));
  }

  @NotNull
  private static List<String> computeCandidateTestsPaths(@NotNull Project project) {
    HaxeTestsBuildFileStore store = HaxeTestsBuildFileStore.getInstance(project);
    List<String> candidates = new ArrayList<>(store.getAllTestsFilePaths());
    List<String> excluded = store.getAllExcludedFilePaths();
    for (VirtualFile hxml : FileTypeIndex.getFiles(HXMLFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
      addConventional(candidates, excluded, hxml.getPath());
    }
    for (VirtualFile xml : FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
      // the cheap path check gates the per-file type detection
      if (HaxeTestsBuildFileStore.isConventionalTestsPath(xml.getPath())
          && LimeProjects.isLimeFamily(HaxeBuildFileScanner.detectType(project, xml))) {
        addConventional(candidates, excluded, xml.getPath());
      }
    }
    return List.copyOf(candidates);
  }

  private static void addConventional(@NotNull List<String> candidates,
                                      @NotNull List<String> excluded,
                                      @NotNull String path) {
    boolean conventional = HaxeTestsBuildFileStore.isConventionalTestsPath(path) && !excluded.contains(path);
    if (conventional && !candidates.contains(path)) {
      candidates.add(path);
    }
  }
}
