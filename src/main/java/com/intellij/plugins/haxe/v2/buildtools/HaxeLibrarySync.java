package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.haxelib.HaxelibCommandUtils;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeActiveBuildFileStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetOptions;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
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
          ReadAction.compute(() -> collectDependencies(project));
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
  private static Map<Module, List<HaxeBuildFileInfo.HaxeLibDependency>> collectDependencies(@NotNull Project project) {
    Map<Module, List<HaxeBuildFileInfo.HaxeLibDependency>> dependencies = new LinkedHashMap<>();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      List<HaxeBuildFile> buildFiles = HaxeBuildFileScanner.scan(module);
      HaxeBuildFile current = currentBuildFile(project, module, buildFiles);
      List<HaxeBuildFile> sources = current != null ? List.of(current) : buildFiles;

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
    HaxeBuildFileInfo raw = HaxeBuildFileInspector.inspect(buildFile);
    HaxeBuildFileType type = buildFile.type();
    boolean limeFamily = type == HaxeBuildFileType.OPENFL || type == HaxeBuildFileType.LIME || type == HaxeBuildFileType.HXP_PROJECT;
    if (!limeFamily) return raw.libraries();

    String targetFlag = HaxeTargetOptions.targetFlagFor(
      type, HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(buildFile.file()));
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(module.getName());
    HaxeBuildFileInfo display = HaxeLimeDisplayService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, () -> sync(project, null));
    return display != null && !display.libraries().isEmpty() ? display.libraries() : raw.libraries();
  }

  /**
   * The build file whose library set the module exposes: the project's active
   * file when this module owns it, else the module's Build command file. Null
   * when neither is configured — the union of all build files applies then.
   */
  @Nullable
  private static HaxeBuildFile currentBuildFile(@NotNull Project project,
                                               @NotNull Module module,
                                               @NotNull List<HaxeBuildFile> scanned) {
    String activePath = HaxeActiveBuildFileStore.getInstance(project).getActiveFilePath();
    HaxeBuildFile active = byPath(scanned, activePath);
    if (active != null && ownedBy(project, active, module)) {
      return active;
    }
    HaxeEnvironmentStore.CompileCommand command = HaxeEnvironmentStore.getInstance(project).getCompileCommand(module.getName());
    if (command != null) {
      HaxeBuildFile commandFile = byPath(scanned, command.buildFilePath());
      if (commandFile != null && ownedBy(project, commandFile, module)) {
        return commandFile;
      }
    }
    return null;
  }

  /** Finds the path among the scanned files, or loads it directly (manually added files live outside the scan). */
  @Nullable
  private static HaxeBuildFile byPath(@NotNull List<HaxeBuildFile> scanned, @Nullable String path) {
    if (StringUtil.isEmptyOrSpaces(path)) return null;
    for (HaxeBuildFile buildFile : scanned) {
      if (buildFile.file().getPath().equals(path)) return buildFile;
    }
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
    return type == null ? null : new HaxeBuildFile(file, type);
  }

  private static boolean ownedBy(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @NotNull Module module) {
    return HaxeCompileCommands.containerIdFor(project, buildFile.file()).equals(module.getName());
  }


  @NotNull
  private static Map<String, List<String>> resolveClasspaths(@NotNull Project project,
                                                             @NotNull Module module,
                                                             @NotNull List<HaxeBuildFileInfo.HaxeLibDependency> dependencies,
                                                             @NotNull ProgressIndicator indicator) {
    VirtualFile workDir = ProjectUtil.guessProjectDir(project);
    Sdk sdk = findSdkForModule(project, module);
    Map<String, List<String>> libraries = new LinkedHashMap<>();
    if (sdk != null && workDir != null) {
      for (HaxeBuildFileInfo.HaxeLibDependency dependency : dependencies) {
        indicator.setText2(dependency.name());
        List<String> output = haxelibPathOutput(sdk, workDir, dependency);
        List<String> classpaths = HaxelibPathParser.parseClasspaths(output);
        if (!classpaths.isEmpty()) {
          String version = HaxelibPathParser.parseVersion(dependency.name(), output);
          if (version == null) {
            version = dependency.version();
          }
          String entryName = version == null ? dependency.name() : dependency.name() + " " + version;
          libraries.put(managedLibraryName(entryName), classpaths);
        }
      }
    }
    return libraries;
  }

  @Nullable
  private static Sdk findSdkForModule(@NotNull Project project, @NotNull Module module) {
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(module.getName());
    if (environmentSdk != null) {
      Sdk sdk = ProjectJdkTable.getInstance().findJdk(environmentSdk);
      if (sdk != null) {
        return sdk;
      }
    }
    return HaxeToolPathResolver.findConfiguredSdk(project);
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
