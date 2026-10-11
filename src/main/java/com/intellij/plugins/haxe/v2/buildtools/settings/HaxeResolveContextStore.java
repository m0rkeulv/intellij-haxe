package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.Map;
import java.util.TreeMap;

/**
 * The build file a user pinned a source file to in the resolve-context
 * switcher, per file path. A pin overrides the automatic choice until
 * "Select automatically" clears it. Stored in the workspace file: a
 * per-developer setting, not project configuration.
 */
// TODO: multiverse: the editor's selected context, onContextPicked and the reset action replace this store - see doc/planned/resolve-context-plan.md, route B
@Service(Service.Level.PROJECT)
@State(name = "HaxeResolveContexts", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class HaxeResolveContextStore implements PersistentStateComponent<HaxeResolveContextStore.State> {

  public static final class State {
    public Map<String, String> buildFileByFile = new TreeMap<>();
  }

  private final @Nullable Project project;
  private State state = new State();

  public HaxeResolveContextStore(@NotNull Project project) {
    this.project = project;
  }

  /** State tests exercise load/get/set without a project; no events fire then. */
  @TestOnly
  public HaxeResolveContextStore() {
    this.project = null;
  }

  @NotNull
  public static HaxeResolveContextStore getInstance(@NotNull Project project) {
    return project.getService(HaxeResolveContextStore.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    if (state.buildFileByFile == null) {
      state.buildFileByFile = new TreeMap<>();
    }
    this.state = state;
    HaxeBuildSettingsListener.publish(project);
  }

  /** The pinned build file's path, or null when the file follows the automatic choice. */
  @Nullable
  public String getPinnedBuildFile(@NotNull VirtualFile file) {
    return state.buildFileByFile.get(file.getPath());
  }

  public void pin(@NotNull VirtualFile file, @NotNull String buildFilePath) {
    state.buildFileByFile.put(file.getPath(), buildFilePath);
    HaxeBuildSettingsListener.publish(project);
  }

  public void unpin(@NotNull VirtualFile file) {
    if (state.buildFileByFile.remove(file.getPath()) != null) {
      HaxeBuildSettingsListener.publish(project);
    }
  }
}
