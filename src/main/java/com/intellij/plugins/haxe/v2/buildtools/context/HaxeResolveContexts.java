package com.intellij.plugins.haxe.v2.buildtools.context;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.plugins.haxe.buildsystem.hxml.HXMLLanguage;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildClasspaths;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.HaxeKnownBuildFiles;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildSettingsTracker;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeResolveContextStore;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which build each file is analysed under. Ownership is by path: among the
 * project's known build files whose sources contain the file, the one with
 * the most specific source directory wins. Several builds sharing that
 * directory (two hxml with the same {@code -cp src}) are told apart by the
 * build whose {@code -main} class the file is, then by the project's active
 * build file, then by tool-window order. A file no build contains falls back
 * to its module's default build, the rule the compiler services used before
 * (the active build file when the module owns it, else the module's Build
 * command). Resolved from a project-level cache of the builds' source
 * directories, invalidated by build-settings events, the project roots, the
 * VFS structure and build-file content - never by the whole-PSI counter.
 */
@Service(Service.Level.PROJECT)
public final class HaxeResolveContexts {

  /** One build and the source directories it compiles; {@code mainFileSuffix} is {@code /pkg/Main.hx} for an hxml {@code -main}. */
  private record Ownership(@NotNull HaxeBuildFile buildFile,
                           @NotNull List<String> sourceDirectories,
                           @Nullable String mainFileSuffix) {
    /** Length of the longest source directory containing the path, or -1 when none does. */
    int specificity(@NotNull String pathAsPrefix) {
      int longest = -1;
      for (String directory : sourceDirectories) {
        if (pathAsPrefix.startsWith(directory + "/")) longest = Math.max(longest, directory.length());
      }
      return longest;
    }

    /** Whether the file is this build's main class. */
    boolean isMain(@NotNull String path) {
      if (mainFileSuffix == null) return false;
      return sourceDirectories.stream().anyMatch(directory -> path.equals(directory + mainFileSuffix));
    }
  }

  private final Project project;

  public HaxeResolveContexts(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeResolveContexts getInstance(@NotNull Project project) {
    return project.getService(HaxeResolveContexts.class);
  }

  /**
   * The context a file is analysed under: the build the user pinned it to
   * while that build still contains it, else the automatic choice; null when
   * neither a build nor the file's module names one. Call in a read action.
   */
  // TODO: multiverse: the provider's getPreferredContext answers this - see doc/planned/resolve-context-plan.md, route B
  @Nullable
  public HaxeResolveContext contextFor(@NotNull VirtualFile file) {
    List<Ownership> owners = owners(file);
    Ownership pinned = pinnedAmong(file, owners);
    if (pinned != null) {
      return new HaxeResolveContext(pinned.buildFile());
    }
    if (!owners.isEmpty()) {
      return new HaxeResolveContext(preferred(file.getPath(), owners).buildFile());
    }
    Module module = ModuleUtilCore.findModuleForFile(file, project);
    HaxeBuildFile fallback = module == null ? null : defaultBuildFileFor(module);
    return fallback == null ? null : new HaxeResolveContext(fallback);
  }

  /** Whether the file's context is a pin that still applies (its build contains the file). Call in a read action. */
  public boolean isPinned(@NotNull VirtualFile file) {
    return pinnedAmong(file, owners(file)) != null;
  }

  @Nullable
  private Ownership pinnedAmong(@NotNull VirtualFile file, @NotNull List<Ownership> owners) {
    String pinnedPath = HaxeResolveContextStore.getInstance(project).getPinnedBuildFile(file);
    if (pinnedPath == null) return null;
    return owners.stream()
      .filter(ownership -> ownership.buildFile().file().getPath().equals(pinnedPath))
      .findFirst()
      .orElse(null);
  }

  /** Every build whose sources contain the file: the most specific first, tool-window order among equals. Call in a read action. */
  // TODO: multiverse: the provider's getContexts answers this - see doc/planned/resolve-context-plan.md, route B
  @NotNull
  public List<HaxeResolveContext> contextsReaching(@NotNull VirtualFile file) {
    return owners(file).stream()
      .map(ownership -> new HaxeResolveContext(ownership.buildFile()))
      .toList();
  }

  /**
   * The module's default build: the project's active build file when this
   * module owns it, else the file of the module's Build command. Null when
   * the module has neither.
   */
  @Nullable
  public HaxeBuildFile defaultBuildFileFor(@NotNull Module module) {
    HaxeBuildFile active = buildFileOwnedBy(HaxeKnownBuildFiles.effectiveActivePath(project), module);
    if (active != null) return active;
    HaxeEnvironmentStore.CompileCommand command = HaxeEnvironmentStore.getInstance(project).getCompileCommand(module.getName());
    return command == null ? null : buildFileOwnedBy(command.buildFilePath(), module);
  }

  @Nullable
  private HaxeBuildFile buildFileOwnedBy(@Nullable String path, @NotNull Module module) {
    if (path == null || path.isBlank()) return null;
    HaxeBuildFile buildFile = HaxeBuildFileScanner.findBuildFile(project, path);
    if (buildFile == null) return null;
    return HaxeContainers.containerIdFor(project, buildFile.file()).equals(module.getName()) ? buildFile : null;
  }

  /**
   * The builds reaching the file: those whose sources contain it, the most
   * specific first and the cache's order (tool-window order) among equals.
   * A library or std file sits in no build's sources, and every build
   * compiles against the std and the module's libraries, so every known
   * build reaches it.
   */
  // TODO: a haxelib library reaches only the builds declaring it; every build is offered until declared libraries are matched per build
  @NotNull
  private List<Ownership> owners(@NotNull VirtualFile file) {
    String pathAsPrefix = file.getPath() + "/";
    List<Ownership> owners = new ArrayList<>();
    for (Ownership ownership : ownerships()) {
      if (ownership.specificity(pathAsPrefix) >= 0) owners.add(ownership);
    }
    if (owners.isEmpty() && ProjectFileIndex.getInstance(project).isInLibrary(file)) {
      return ownerships();
    }
    owners.sort((a, b) -> Integer.compare(b.specificity(pathAsPrefix), a.specificity(pathAsPrefix)));
    return owners;
  }

  /** Among the most specific owners (every build, for a library file): the file's -main build, else the active build file, else the first. */
  @NotNull
  private Ownership preferred(@NotNull String path, @NotNull List<Ownership> owners) {
    String pathAsPrefix = path + "/";
    int best = owners.getFirst().specificity(pathAsPrefix);
    List<Ownership> candidates = owners.stream()
      .filter(ownership -> ownership.specificity(pathAsPrefix) == best)
      .toList();
    for (Ownership candidate : candidates) {
      if (candidate.isMain(path)) return candidate;
    }
    String activePath = HaxeKnownBuildFiles.effectiveActivePath(project);
    for (Ownership candidate : candidates) {
      if (candidate.buildFile().file().getPath().equals(activePath)) return candidate;
    }
    return candidates.getFirst();
  }

  @NotNull
  private List<Ownership> ownerships() {
    return CachedValuesManager.getManager(project).getCachedValue(project, () ->
      CachedValueProvider.Result.create(computeOwnerships(), dependencies()));
  }

  private Object @NotNull [] dependencies() {
    PsiModificationTracker psiTracker = PsiModificationTracker.getInstance(project);
    return new Object[]{
      HaxeBuildSettingsTracker.getInstance(project),
      ProjectRootModificationTracker.getInstance(project),
      // TODO: any file creation (a build's output) re-sweeps the build files on the next ask; a build-file-only tracker would narrow it
      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
      psiTracker.forLanguage(HXMLLanguage.INSTANCE),
      psiTracker.forLanguage(XMLLanguage.INSTANCE)};
  }

  /** One inspection per known build file: its classpaths decide ownership, its main class the tie-break. */
  @NotNull
  private List<Ownership> computeOwnerships() {
    List<Ownership> ownerships = new ArrayList<>();
    for (HaxeBuildFile buildFile : HaxeKnownBuildFiles.all(project)) {
      // a plain hxp script generates its compiler arguments in code - nothing to derive statically
      if (buildFile.type() == HaxeBuildFileType.HXP_SCRIPT) continue;
      HaxeBuildFileInfo info = HaxeBuildSections.inspectSelected(project, buildFile);
      List<String> sourceDirectories = HaxeBuildClasspaths.sourceDirectories(project, buildFile, info.classpaths());
      ownerships.add(new Ownership(buildFile, List.copyOf(sourceDirectories), mainFileSuffix(buildFile)));
    }
    return List.copyOf(ownerships);
  }

  /** The hxml's {@code -main} class as a path suffix under a source directory; null without one. */
  // TODO: the lime family's <app main="..."/> is not read yet, so two project.xml sharing a source folder tie on the active file
  @Nullable
  private String mainFileSuffix(@NotNull HaxeBuildFile buildFile) {
    if (buildFile.type() != HaxeBuildFileType.HXML) return null;
    String content = HaxeBuildSections.selectedSectionContent(project, buildFile);
    String mainClass = content == null ? null : HxmlFileParser.mainClass(content);
    return mainClass == null ? null : "/" + mainClass.replace(".", "/") + ".hx";
  }
}
