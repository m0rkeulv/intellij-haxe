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

import java.util.TreeMap;
import java.util.Map;
import org.jetbrains.annotations.TestOnly;

/**
 * Remembers the target platform the user picked per build file in the Haxe tool
 * window. Stored in the workspace file: target choice is a per-developer setting,
 * not project configuration. Will fold into the planned profiles concept.
 */
@Service(Service.Level.PROJECT)
@State(name = "HaxeToolWindowTargets", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class HaxeTargetSelectionStore implements PersistentStateComponent<HaxeTargetSelectionStore.State> {
  private final @Nullable Project project;

  public HaxeTargetSelectionStore(@NotNull Project project) {
    this.project = project;
  }

  /** State tests exercise load/get/set without a project; no events fire then. */
  @TestOnly
  public HaxeTargetSelectionStore() {
    this.project = null;
  }

  private void notifyChanged() {
    if (project != null) {
      project.getMessageBus().syncPublisher(HaxeBuildSettingsListener.TOPIC).buildSettingsChanged();
    }
  }


  public static final class State {
    public Map<String, String> targetsByFile = new TreeMap<>();
  }

  private State state = new State();

  @NotNull
  public static HaxeTargetSelectionStore getInstance(@NotNull Project project) {
    return project.getService(HaxeTargetSelectionStore.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    if (state.targetsByFile == null) {
      state.targetsByFile = new TreeMap<>();
    }
    this.state = state;
    notifyChanged();
  }

  @Nullable
  public String getSelectedTargetId(@NotNull VirtualFile buildFile) {
    return state.targetsByFile.get(buildFile.getPath());
  }

  public void setSelectedTargetId(@NotNull VirtualFile buildFile, @NotNull String targetId) {
    state.targetsByFile.put(buildFile.getPath(), targetId);
    notifyChanged();
  }
}
