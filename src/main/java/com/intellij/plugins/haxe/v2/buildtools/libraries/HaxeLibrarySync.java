package com.intellij.plugins.haxe.v2.buildtools.libraries;

import com.intellij.plugins.haxe.v2.buildtools.info.HaxeLimeProjectInfoService;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.HaxeKnownBuildFiles;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.haxelib.HaxelibCommandUtils;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.testing.HaxeTestFrameworks;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches each module's haxelib dependencies as module-level libraries, so they
 * appear under External Libraries and feed resolve scopes. The dependency set
 * follows the module's CURRENT build file (the project's active file when this
 * module owns it, else the module's Build command file) — code must not resolve
 * against libraries only a non-current build file pulls in. Modules without a
 * current file fall back to the union of all their build files. Classpaths come
 * from `haxelib path`, which includes transitive dependencies. Managed entries
 * carry a name prefix so the sync can remove stale ones without touching
 * user-defined libraries.
 */
@CustomLog
public final class HaxeLibrarySync {

  public static final String MANAGED_PREFIX = "haxelib: ";

  private HaxeLibrarySync() {
  }

  @NotNull
  public static String managedLibraryName(@NotNull String libraryName) {
    return MANAGED_PREFIX + libraryName;
  }

  /**
   * Whether a synced library-table entry belongs to the given lib. Entries
   * carry the resolved version as a suffix ("haxelib: openfl 9.5.0"), so an
   * exact-name lookup would miss them.
   */
  public static boolean isManagedEntryFor(@Nullable String entryName, @NotNull String libraryName) {
    if (entryName == null) return false;
    String base = managedLibraryName(libraryName);
    return entryName.equals(base) || entryName.startsWith(base + " ");
  }

  /** Resolves and applies module libraries in the background; {@code onFinished} runs on the EDT. */
  public static void sync(@NotNull Project project, @Nullable Runnable onFinished) {
    new Task.Backgroundable(project, HaxeBundle.message("haxe.library.sync.progress"), false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        Map<Module, List<HaxeBuildFileInfo.HaxeLibDependency>> dependencies =
          ReadAction.computeBlocking(() -> collectDependencies(project));
        Map<String, Map<String, List<String>>> byModuleName = new LinkedHashMap<>();
        dependencies.forEach((module, moduleDependencies) ->
          byModuleName.put(module.getName(), resolveClasspaths(project, module, moduleDependencies, indicator)));
        if (!project.isDisposed()) {
          HaxeLibraryWorkspace.getInstance(project).applyAllAsync(byModuleName, onFinished);
        }
      }
    }.queue();
  }

  @NotNull
  static Map<Module, List<HaxeBuildFileInfo.HaxeLibDependency>> collectDependencies(@NotNull Project project) {
    Map<Module, List<HaxeBuildFileInfo.HaxeLibDependency>> dependencies = new LinkedHashMap<>();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      List<HaxeBuildFile> buildFiles = HaxeKnownBuildFiles.forModule(project, module);
      List<HaxeBuildFile> sources = dependencySources(project, module, buildFiles);

      List<HaxeBuildFileInfo.HaxeLibDependency> moduleDependencies = new ArrayList<>();
      for (HaxeBuildFile buildFile : sources) {
        for (HaxeBuildFileInfo.HaxeLibDependency dependency : effectiveLibraries(project, module, buildFile)) {
          boolean known = moduleDependencies.stream().anyMatch(existing -> existing.name().equals(dependency.name()));
          if (!known) {
            moduleDependencies.add(dependency);
          }
        }
      }
      addHxpToolchainLibraries(buildFiles, moduleDependencies);
      dependencies.put(module, moduleDependencies);
    }
    return dependencies;
  }

  /**
   * Modules holding .hxp build scripts get the hxp and lime haxelibs attached
   * as regular module libraries, so the scripts (importing hxp.* /
   * lime.tools.* from the toolchain) resolve with completion. Deliberate
   * trade-off: the module's own sources can reference these libs too, and
   * only the compiler will complain.
   */
  private static void addHxpToolchainLibraries(@NotNull List<HaxeBuildFile> buildFiles,
                                               @NotNull List<HaxeBuildFileInfo.HaxeLibDependency> dependencies) {
    boolean hasHxpScript = buildFiles.stream()
      .anyMatch(file -> file.type() == HaxeBuildFileType.HXP_PROJECT || file.type() == HaxeBuildFileType.HXP_SCRIPT);
    if (!hasHxpScript) return;
    for (String lib : List.of("hxp", "lime")) {
      boolean known = dependencies.stream().anyMatch(existing -> existing.name().equals(lib));
      if (!known) {
        dependencies.add(new HaxeBuildFileInfo.HaxeLibDependency(lib, null));
      }
    }
  }

  /**
   * A build file's library dependencies with conditionals evaluated: lime-family
   * files use the LimeProjectParser evaluation for the selected target — the raw
   * xml parse lists every {@code <haxelib>} regardless of its if/unless condition.
   * The raw parse remains the fallback while the evaluation is pending or when
   * only the legacy lime-display path ran (its hxml has no -lib entries).
   */
  @NotNull
  private static List<HaxeBuildFileInfo.HaxeLibDependency> effectiveLibraries(@NotNull Project project,
                                                                              @NotNull Module module,
                                                                              @NotNull HaxeBuildFile buildFile) {
    HaxeBuildFileInfo raw = HaxeBuildSections.inspectSelected(project, buildFile);
    HaxeBuildFileType type = buildFile.type();
    if (!LimeProjects.isLimeFamily(type)) return raw.libraries();

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(module.getName());
    HaxeBuildFileInfo display = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, () -> sync(project, null));
    return display != null && !display.libraries().isEmpty() ? display.libraries() : raw.libraries();
  }

  /**
   * The build files whose library sets the module exposes: its CURRENT build
   * file (the project's effective active file when this module owns it, else
   * the module's Build command file), plus its tests build files — the module's
   * source roots hold the test sources too, so every tests build's libraries
   * must resolve alongside the main build's. Without a current file the union
   * of all known build files applies.
   */
  @NotNull
  private static List<HaxeBuildFile> dependencySources(@NotNull Project project,
                                                       @NotNull Module module,
                                                       @NotNull List<HaxeBuildFile> buildFiles) {
    HaxeBuildFile current = currentBuildFile(project, module, buildFiles);
    List<HaxeBuildFile> sources = new ArrayList<>(current != null ? List.of(current) : buildFiles);

    for (HaxeBuildFile tests : testsBuildFiles(project, module, buildFiles)) {
      boolean known = sources.stream()
        .anyMatch(source -> source.file().getPath().equals(tests.file().getPath()));
      if (!known) {
        sources.add(tests);
      }
    }
    return sources;
  }

  @Nullable
  private static HaxeBuildFile currentBuildFile(@NotNull Project project,
                                               @NotNull Module module,
                                               @NotNull List<HaxeBuildFile> scanned) {
    String activePath = HaxeKnownBuildFiles.effectiveActivePath(project);
    HaxeBuildFile active = byPath(project, scanned, activePath);
    if (active != null && ownedBy(project, active, module)) {
      return active;
    }
    HaxeEnvironmentStore.CompileCommand command = HaxeEnvironmentStore.getInstance(project).getCompileCommand(module.getName());
    if (command != null) {
      HaxeBuildFile commandFile = byPath(project, scanned, command.buildFilePath());
      if (commandFile != null && ownedBy(project, commandFile, module)) {
        return commandFile;
      }
    }
    return null;
  }

  /** The container's marked (or convention-suggested) tests build files that this module owns - gating shared via {@link HaxeTestFrameworks#testsBuildPaths}. */
  @NotNull
  private static List<HaxeBuildFile> testsBuildFiles(@NotNull Project project,
                                                     @NotNull Module module,
                                                     @NotNull List<HaxeBuildFile> buildFiles) {
    Map<String, List<HaxeBuildFileInfo.HaxeLibDependency>> librariesByPath = new LinkedHashMap<>();
    for (HaxeBuildFile buildFile : buildFiles) {
      librariesByPath.put(buildFile.file().getPath(), HaxeBuildSections.inspectSelected(project, buildFile).libraries());
    }
    List<String> testsPaths = HaxeTestFrameworks.testsBuildPaths(project, module.getName(), librariesByPath);
    List<HaxeBuildFile> owned = new ArrayList<>();
    for (String testsPath : testsPaths) {
      HaxeBuildFile tests = byPath(project, buildFiles, testsPath);
      if (tests != null && ownedBy(project, tests, module)) {
        owned.add(tests);
      }
    }
    return owned;
  }

  /** Finds the path among the scanned files, or loads it directly (manually added files live outside the scan). */
  @Nullable
  private static HaxeBuildFile byPath(@NotNull Project project, @NotNull List<HaxeBuildFile> scanned, @Nullable String path) {
    if (StringUtil.isEmptyOrSpaces(path)) return null;
    for (HaxeBuildFile buildFile : scanned) {
      if (buildFile.file().getPath().equals(path)) return buildFile;
    }
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    return type == null ? null : new HaxeBuildFile(file, type);
  }

  private static boolean ownedBy(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @NotNull Module module) {
    return HaxeContainers.containerIdFor(project, buildFile.file()).equals(module.getName());
  }


  @NotNull
  private static Map<String, List<String>> resolveClasspaths(@NotNull Project project,
                                                             @NotNull Module module,
                                                             @NotNull List<HaxeBuildFileInfo.HaxeLibDependency> dependencies,
                                                             @NotNull ProgressIndicator indicator) {
    VirtualFile workDir = ProjectUtil.guessProjectDir(project);
    Sdk sdk = HaxeToolPathResolver.resolveSdk(project, module.getName());
    Map<String, List<String>> libraries = new LinkedHashMap<>();
    if (sdk != null && workDir != null) {
      for (HaxeBuildFileInfo.HaxeLibDependency dependency : dependencies) {
        indicator.setText2(dependency.name());
        List<String> output = haxelibPathOutput(sdk, workDir, dependency);
        // one External Libraries entry PER library in the output: `haxelib
        // path` prints the requested lib and its transitive dependencies, and
        // attaching the whole closure to the requested lib's entry mounts
        // dependency sources under the wrong library (and duplicates type
        // definitions when entries disagree on a dependency's version)
        for (HaxelibPathParser.LibrarySection section : HaxelibPathParser.parseSections(dependency.name(), output)) {
          if (section.classpaths().isEmpty()) continue;
          String version = section.version() != null ? section.version()
                                                     : section.name().equals(dependency.name()) ? dependency.version() : null;
          String entryName = version == null ? section.name() : section.name() + " " + version;
          libraries.merge(managedLibraryName(entryName), section.classpaths(), HaxeLibrarySync::unionPreservingOrder);
        }
      }
    }
    return libraries;
  }

  @NotNull
  private static List<String> unionPreservingOrder(@NotNull List<String> first, @NotNull List<String> second) {
    List<String> union = new ArrayList<>(first);
    for (String path : second) {
      if (!union.contains(path)) {
        union.add(path);
      }
    }
    return union;
  }

  @NotNull
  private static List<String> haxelibPathOutput(@NotNull Sdk sdk,
                                                @NotNull VirtualFile workDir,
                                                @NotNull HaxeBuildFileInfo.HaxeLibDependency dependency) {
    String spec = dependency.name();
    // a release version like 1.2.3 can be passed to `haxelib path`; git/path specs cannot
    if (dependency.version() != null && dependency.version().matches("\\d+(\\.\\d+)*([-.].*)?")) {
      spec += ":" + dependency.version();
    }
    return HaxelibCommandUtils.issueHaxelibCommand(sdk, workDir, "path", spec);
  }

}
