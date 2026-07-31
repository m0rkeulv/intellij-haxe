package com.intellij.plugins.haxe.v2.buildtools;

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
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeActiveBuildFileStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
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
public final class HaxeDefineContextService implements Disposable {

  private record Snapshot(@NotNull String key, @NotNull Map<String, String> defines) {
  }

  private final Project project;
  private volatile Snapshot snapshot;

  public HaxeDefineContextService(@NotNull Project project) {
    this.project = project;
  }

  public static HaxeDefineContextService getInstance(@NotNull Project project) {
    return project.getService(HaxeDefineContextService.class);
  }

  /**
   * The current define context, or null when the project has no v2 active build
   * file (legacy detection applies then). Cached; recomputed when the build
   * file, target selection or environment overrides change.
   */
  @Nullable
  public Map<String, String> getActiveDefines() {
    String path = HaxeActiveBuildFileStore.getInstance(project).getActiveFilePath();
    if (StringUtil.isEmptyOrSpaces(path)) return null;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return null;

    return ReadAction.computeBlocking(() -> {
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
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
  }

  /**
   * Recomputes the context in the background and reparses all haxe files when it
   * actually changed, so stubs and highlighting pick up the new conditionals.
   * Cheap when nothing changed — safe to call from every tree refresh.
   */
  public void refreshAsync() {
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      if (project.isDisposed()) return;
      Snapshot before = snapshot;
      snapshot = null;
      Map<String, String> after = getActiveDefines();
      boolean changed = before != null && !Objects.equals(before.defines(), after);
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
    List<HaxeEnvironmentStore.EnvironmentDefine> overrides = HaxeEnvironmentStore.getInstance(project).getDefines(containerId);
    return file.getPath() + '|' + type + '|' + stamp + '|' + targetId + '|' + sdkName + '|' + overrides;
  }

  @NotNull
  private Map<String, String> compute(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
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

    String containerId = HaxeContainers.containerIdFor(project, file);
    for (HaxeEnvironmentStore.EnvironmentDefine override : HaxeEnvironmentStore.getInstance(project).getDefines(containerId)) {
      if (override.effect() == HaxeEnvironmentStore.DefineEffect.REMOVE) {
        defines.remove(override.name());
      }
      else {
        defines.put(override.name(), override.value().isEmpty() ? "true" : override.value());
      }
    }
    return defines;
  }

  /**
   * The build file's parsed info; lime-family files use the selected target's
   * `lime display` result when available (raw xml defines as the fallback until
   * the background run lands, which then re-triggers a refresh).
   */
  @NotNull
  private HaxeBuildFileInfo effectiveInfo(@NotNull HaxeBuildFile buildFile) {
    HaxeBuildFileInfo raw = HaxeBuildFileInspector.inspect(buildFile);
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
