package com.intellij.plugins.haxe.v2.buildtools.context;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.buildsystem.hxml.HXMLLanguage;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildClasspaths;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildEventListener;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.HaxeKnownBuildFiles;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildSettingsListener;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeResolveContextStore;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which build each file is analysed under. Ownership is by path: among the
 * project's known build files whose sources contain the file, those with the
 * most specific source directory are its closest owners. The user's pin wins
 * while its build contains the file, then the followed context (the build a
 * navigation carried over) while it is among the closest owners. Otherwise
 * the closest owners (two hxml with the same {@code -cp src}) are told apart
 * by the build whose {@code -main} class the file is, then the project's
 * active build file, then the Build command of the file's container, then
 * tool-window order. A file no build contains falls back to its module's
 * default build ({@link #defaultBuildFileFor}).
 * <p>
 * The builds' source directories are cached for the project and recomputed
 * when a build file is registered, appears, moves, is saved or deleted,
 * when a selection or environment that changes a build's classpaths or
 * evaluation changes, when an evaluation lands, when the project roots
 * change, and on edits of hxml or xml files - never on a build's output
 * being written or on an unrelated setting.
 */
@Service(Service.Level.PROJECT)
public final class HaxeResolveContexts implements Disposable {

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
  /** Bumped by every build-file setting or event the source directories derive from. */
  private final SimpleModificationTracker buildFileInputs = new SimpleModificationTracker();

  public HaxeResolveContexts(@NotNull Project project) {
    this.project = project;
    MessageBusConnection connection = project.getMessageBus().connect(this);
    connection.subscribe(HaxeBuildSettingsListener.TOPIC, new BuildFileSettings());
    connection.subscribe(HaxeBuildEventListener.TOPIC, new BuildFileEvents());
  }

  @Override
  public void dispose() {
  }

  @NotNull
  public static HaxeResolveContexts getInstance(@NotNull Project project) {
    return project.getService(HaxeResolveContexts.class);
  }

  /**
   * The context a file is analysed under: the user's pin, else the followed
   * context - each only while its build still contains the file - else the
   * automatic choice; null when neither a build nor the file's module names
   * one. Call in a read action.
   */
  // TODO: multiverse: the provider's getPreferredContext answers this
  @Nullable
  public HaxeResolveContext contextFor(@NotNull VirtualFile file) {
    List<Ownership> owners = owners(file);
    Ownership chosen = chosenAmong(file, owners);
    if (chosen != null) {
      return new HaxeResolveContext(chosen.buildFile());
    }
    if (!owners.isEmpty()) {
      return new HaxeResolveContext(preferred(file, owners).buildFile());
    }
    Module module = ModuleUtilCore.findModuleForFile(file, project);
    HaxeBuildFile fallback = module == null ? null : defaultBuildFileFor(module);
    return fallback == null ? null : new HaxeResolveContext(fallback);
  }

  /** Whether the file's context is a pin that still applies (its build contains the file). Call in a read action. */
  public boolean isPinned(@NotNull VirtualFile file) {
    return pinnedAmong(file, owners(file)) != null;
  }

  /** Whether the file shows the context a navigation gave it (no pin, and that build is among its closest owners). Call in a read action. */
  public boolean isFollowed(@NotNull VirtualFile file) {
    List<Ownership> owners = owners(file);
    return pinnedAmong(file, owners) == null && followedAmong(file, owners) != null;
  }

  /**
   * Gives {@code to} the context of {@code from}: a navigation landing in a
   * file several builds reach equally (a library or a shared source) shows it
   * as the file it came from sees it. Only among {@code to}'s closest owners:
   * a root hxml's implicit classpath reaches the whole project, so a file
   * with a closer owner keeps it. Nothing changes for a pinned file or when
   * the two already agree. Returns whether the context changed. Call in a
   * read action.
   */
  public boolean follow(@NotNull VirtualFile from, @NotNull VirtualFile to) {
    HaxeResolveContextStore store = HaxeResolveContextStore.getInstance(project);
    if (store.getPinnedBuildFile(to) != null) return false;
    HaxeResolveContext origin = contextFor(from);
    if (origin == null) return false;
    List<Ownership> owners = owners(to);
    if (owners.isEmpty()) return false;
    List<Ownership> closest = closestOwners(to.getPath(), owners);
    if (closest.size() < 2 || findOwner(closest, origin.path()) == null) return false;
    HaxeResolveContext current = contextFor(to);
    if (current != null && current.path().equals(origin.path())) return false;
    store.follow(to, origin.path());
    return true;
  }

  /** The user's pin, else the context a navigation gave the file - each only while its build contains the file. */
  @Nullable
  private Ownership chosenAmong(@NotNull VirtualFile file, @NotNull List<Ownership> owners) {
    Ownership pinned = pinnedAmong(file, owners);
    return pinned != null ? pinned : followedAmong(file, owners);
  }

  @Nullable
  private Ownership pinnedAmong(@NotNull VirtualFile file, @NotNull List<Ownership> owners) {
    String pinnedPath = HaxeResolveContextStore.getInstance(project).getPinnedBuildFile(file);
    return pinnedPath == null ? null : findOwner(owners, pinnedPath);
  }

  /** The followed build applies only while it stays among the file's closest owners - the rule {@link #follow} chose it by. */
  @Nullable
  private Ownership followedAmong(@NotNull VirtualFile file, @NotNull List<Ownership> owners) {
    String followedPath = HaxeResolveContextStore.getInstance(project).getFollowedBuildFile(file);
    if (followedPath == null || owners.isEmpty()) return null;
    return findOwner(closestOwners(file.getPath(), owners), followedPath);
  }

  @Nullable
  private static Ownership findOwner(@NotNull List<Ownership> owners, @Nullable String buildFilePath) {
    if (buildFilePath == null) return null;
    return owners.stream()
      .filter(ownership -> ownership.buildFile().file().getPath().equals(buildFilePath))
      .findFirst()
      .orElse(null);
  }

  /** Every build whose sources contain the file: the most specific first, tool-window order among equals. Call in a read action. */
  // TODO: multiverse: the provider's getContexts answers this
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

  /**
   * Among the closest owners (every build, for a library file): the file's
   * -main build, else the active build file, else the Build command of the
   * file's container, else the first in tool-window order.
   */
  @NotNull
  private Ownership preferred(@NotNull VirtualFile file, @NotNull List<Ownership> owners) {
    String path = file.getPath();
    List<Ownership> candidates = closestOwners(path, owners);
    for (Ownership candidate : candidates) {
      if (candidate.isMain(path)) return candidate;
    }
    Ownership active = findOwner(candidates, HaxeKnownBuildFiles.effectiveActivePath(project));
    if (active != null) return active;
    Ownership command = findOwner(candidates, compileCommandPath(file));
    return command != null ? command : candidates.getFirst();
  }

  /** The build file of the Build command set for the file's container, or null. */
  @Nullable
  private String compileCommandPath(@NotNull VirtualFile file) {
    String containerId = HaxeContainers.containerIdFor(project, file);
    HaxeEnvironmentStore.CompileCommand command = HaxeEnvironmentStore.getInstance(project).getCompileCommand(containerId);
    return command == null ? null : command.buildFilePath();
  }

  /** The owners tied for the most specific source directory containing the path - all of them for a library file. Expects {@code owners} sorted most specific first. */
  @NotNull
  private static List<Ownership> closestOwners(@NotNull String path, @NotNull List<Ownership> owners) {
    String pathAsPrefix = path + "/";
    int best = owners.getFirst().specificity(pathAsPrefix);
    return owners.stream()
      .filter(ownership -> ownership.specificity(pathAsPrefix) == best)
      .toList();
  }

  @NotNull
  private List<Ownership> ownerships() {
    return CachedValuesManager.getManager(project).getCachedValue(project, () ->
      CachedValueProvider.Result.create(computeOwnerships(), dependencies()));
  }

  private Object @NotNull [] dependencies() {
    PsiModificationTracker psiTracker = PsiModificationTracker.getInstance(project);
    return new Object[]{
      buildFileInputs,
      ProjectRootModificationTracker.getInstance(project),
      psiTracker.forLanguage(HXMLLanguage.INSTANCE),
      psiTracker.forLanguage(XMLLanguage.INSTANCE)};
  }

  /**
   * The choices that change which build files exist or what they compile.
   * The active file and a pin are read per ask, not cached. */
  private final class BuildFileSettings implements HaxeBuildSettingsListener {
    @Override
    public void stateLoaded() {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void buildFilesChanged() {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void targetSelected(@NotNull String buildFilePath) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void sectionSelected(@NotNull String buildFilePath) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void environmentChanged(@NotNull String containerId, @NotNull EnvironmentAspect aspect) {
      buildFileInputs.incModificationCount();
    }
  }

  /** The facts that change the build files on disk or their evaluated classpaths. */
  private final class BuildFileEvents implements HaxeBuildEventListener {
    @Override
    public void buildFileContentChanged(@NotNull String path) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void buildFileAdded(@NotNull String path) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void buildFileDeleted(@NotNull String path) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void evaluationLanded(@NotNull String buildFilePath) {
      buildFileInputs.incModificationCount();
    }

    @Override
    public void derivedCachesCleared() {
      buildFileInputs.incModificationCount();
    }
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
