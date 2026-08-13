package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.plugins.haxe.v2.buildtools.settings.EnvironmentDefine;
import com.intellij.plugins.haxe.v2.buildtools.settings.DefineEffect;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.util.HaxeUtil;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The IDE's conditional-compilation define context, derived from the v2 build
 * configuration: the project's ACTIVE build file's defines (via `lime display`
 * for xml projects — conditionals evaluated, toolchain defines included)
 * overlaid with the owning container's IDE Define overrides. Feeds
 * {@code HaxeDefineDetectionManager.getAllDefinitions()}, i.e. the parsing and
 * indexing of every haxe file — which is why a context change triggers a
 * project-wide reparse.
 */
@Service(Service.Level.PROJECT)
public final class HaxeDefineContextService implements Disposable, HaxeBuildSettingsListener {

  private record Snapshot(@NotNull String key, @NotNull Map<String, String> defines) {
  }

  private final Project project;
  private volatile Snapshot snapshot;

  public HaxeDefineContextService(@NotNull Project project) {
    this.project = project;
    // any build-settings mutation invalidates the derived define context
    project.getMessageBus().connect().subscribe(HaxeBuildSettingsListener.TOPIC, this);
  }

  /**
   * Drops the derived context and recomputes in the background — a settings
   * mutation must reach parsing (reparse) and highlighting WITHOUT relying on
   * the tool window being open to call {@link #refreshAsync()}.
   */
  @Override
  public void buildSettingsChanged() {
    snapshot = null;
    fastState = null;
    refreshAsync();
  }

  public static HaxeDefineContextService getInstance(@NotNull Project project) {
    return project.getService(HaxeDefineContextService.class);
  }

  /**
   * Lock-free identity of every input reachable without VFS or read-action
   * work: the active path, the file's content stamp and the three stores'
   * modification counters. A match short-circuits the whole computation -
   * this runs per candidate inside index lookups, so the fast path must not
   * touch findFileByPath or take a read action.
   */
  private record FastState(@NotNull String path, @NotNull VirtualFile file, long contentStamp,
                           @Nullable Map<String, String> defines) {
  }

  /**
   * Sentinel for {@link #lastComputed}: no consumer has been handed a define
   * context yet, so nothing was parsed against one and no reparse is owed.
   * Identity-compared, never handed out.
   */
  private static final Map<String, String> NEVER_HANDED_OUT = new LinkedHashMap<>();

  private volatile FastState fastState;
  /** The defines most recently handed to a consumer — what current PSI state was parsed against. */
  private volatile Map<String, String> lastComputed = NEVER_HANDED_OUT;

  private static long contentStamp(@NotNull VirtualFile file) {
    Document document = FileDocumentManager.getInstance().getCachedDocument(file);
    return document != null ? document.getModificationStamp() : file.getModificationStamp();
  }

  /**
   * The current define context, or null when the project has no v2 active build
   * file (legacy detection applies then). Cached; recomputed when the build
   * file, target selection or environment overrides change.
   */
  @Nullable
  public Map<String, String> getActiveDefines() {
    String path = HaxeKnownBuildFiles.effectiveActivePath(project);
    if (StringUtil.isEmptyOrSpaces(path)) {
      // record the null handout: files parsed now use the LEGACY define
      // context, and activating a build file later must trigger a reparse
      lastComputed = null;
      return null;
    }

    FastState fast = fastState;
    boolean fastHit = fast != null
                      && fast.path().equals(path)
                      && fast.file().isValid()
                      && contentStamp(fast.file()) == fast.contentStamp();
    if (fastHit) {
      return fast.defines();
    }

    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) {
      lastComputed = null;
      return null;
    }

    Map<String, String> result = ReadAction.computeBlocking(() -> {
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
      if (type == null) return null;

      String key = cacheKey(file, type);
      Snapshot current = snapshot;
      if (current != null && current.key().equals(key)) {
        return current.defines();
      }
      Map<String, String> defines = compute(file, type);
      snapshot = new Snapshot(key, defines);
      return defines;
    });
    fastState = new FastState(path, file, contentStamp(file), result);
    lastComputed = result;
    return result;
  }

  /**
   * Recomputes the context in the background and reparses all haxe files when it
   * actually changed, so stubs and highlighting pick up the new conditionals.
   * Cheap when nothing changed — safe to call from every tree refresh.
   */
  public void refreshAsync() {
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      if (project.isDisposed()) return;
      // compare against the defines last handed to consumers, NOT the snapshot
      // cache: the invalidation topic clears the snapshot synchronously before
      // this runs. The sentinel keeps project open cheap (nothing was handed
      // out, nothing to reparse) while a recorded null handout still reparses
      // on the null -> non-null transition (build file activated after files
      // were parsed against the legacy context).
      Map<String, String> before = lastComputed;
      snapshot = null;
      fastState = null;
      Map<String, String> after = getActiveDefines();
      lastComputed = after;
      boolean changed = before != NEVER_HANDED_OUT && !Objects.equals(before, after);
      if (changed) {
        // false = do not mark the LEGACY auto-import dirty; v2 has its own tracker
        HaxeUtil.reparseProjectFiles(project, false);
      }
    });
  }

  /** Cheap identity of every input; a mismatch invalidates the cached context. */
  @NotNull
  private String cacheKey(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    Document document = FileDocumentManager.getInstance().getCachedDocument(file);
    long stamp = document != null ? document.getModificationStamp() : file.getModificationStamp();
    String containerId = HaxeContainers.containerIdFor(project, file);
    String targetId = HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file);
    String sdkName = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    List<EnvironmentDefine> overrides = HaxeEnvironmentStore.getInstance(project).getDefines(containerId);
    return file.getPath() + '|' + type + '|' + stamp + '|' + targetId + '|' + sdkName + '|' + overrides;
  }

  @NotNull
  private Map<String, String> compute(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    Map<String, String> defines = baseDefines(file, type);

    String containerId = HaxeContainers.containerIdFor(project, file);
    for (EnvironmentDefine override : HaxeEnvironmentStore.getInstance(project).getDefines(containerId)) {
      if (override.effect() == DefineEffect.REMOVE) {
        defines.remove(override.name());
      }
      else {
        defines.put(override.name(), override.value().isEmpty() ? "true" : override.value());
      }
    }
    return defines;
  }

  /** The build context's defines BEFORE the container's environment overrides apply. */
  @NotNull
  private Map<String, String> baseDefines(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    HaxeBuildFile buildFile = new HaxeBuildFile(file, type);
    HaxeBuildFileInfo info = effectiveInfo(buildFile);

    Map<String, String> defines = new LinkedHashMap<>();
    for (HaxeBuildFileInfo.HaxeDefine define : info.defines()) {
      defines.put(define.name(), define.value() != null ? define.value() : "true");
    }
    // the compiler implicitly defines the target (hl, js, sys, ...) - the std
    // library's per-target sources are gated on exactly these
    if (info.target() != null) {
      info.target().getDefinitions().forEach(definition -> defines.putIfAbsent(definition, "true"));
    }
    return defines;
  }

  /**
   * Whether the ACTIVE build context defines the name before environment
   * overrides apply — the define quickfix uses this to decide between merely
   * dropping its own override and masking a build-file define with a REMOVE
   * entry. False when no v2 active build file is configured.
   */
  public boolean isDefinedWithoutOverrides(@NotNull String name) {
    String path = HaxeKnownBuildFiles.effectiveActivePath(project);
    if (StringUtil.isEmptyOrSpaces(path)) return false;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return false;
    return Boolean.TRUE.equals(ReadAction.compute(() -> {
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
      if (type == null) return false;
      return baseDefines(file, type).containsKey(name);
    }));
  }

  /** The container owning the ACTIVE build file, or null without one — where define overrides belong. */
  @Nullable
  public String activeContainerId() {
    String path = HaxeKnownBuildFiles.effectiveActivePath(project);
    if (StringUtil.isEmptyOrSpaces(path)) return null;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return null;
    return HaxeContainers.containerIdFor(project, file);
  }

  /**
   * The build file's parsed info; lime-family files use the selected target's
   * `lime display` result when available (raw xml defines as the fallback until
   * the background run lands, which then re-triggers a refresh).
   */
  @NotNull
  private HaxeBuildFileInfo effectiveInfo(@NotNull HaxeBuildFile buildFile) {
    HaxeBuildFileInfo raw = HaxeBuildSections.inspectSelected(project, buildFile);
    HaxeBuildFileType type = buildFile.type();
    if (!LimeProjects.isLimeFamily(type)) return raw;

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    String containerId = HaxeContainers.containerIdFor(project, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    HaxeBuildFileInfo display = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, this::refreshAsync);
    return display != null ? display : raw;
  }

  @Override
  public void dispose() {
  }
}
