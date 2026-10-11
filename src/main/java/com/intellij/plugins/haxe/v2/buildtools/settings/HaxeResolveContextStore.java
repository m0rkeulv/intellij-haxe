package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.Map;
import java.util.TreeMap;

/**
 * The build file a user pinned a source file to in the resolve-context
 * switcher, per file path, and - for the session only - the context a
 * navigation gave a file. A pin overrides the automatic choice and a
 * followed context until "Select automatically" clears both. Pins live in
 * the workspace file: a per-developer setting, not project configuration.
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
  /** By file path; never persisted - a navigation's choice does not outlive the session. */
  private final Map<String, String> followedBuildFileByFile = new HashMap<>();

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
    HaxeResolveContextListener.publish(project);
  }

  /** The pinned build file's path, or null when the file follows the automatic choice. */
  @Nullable
  public String getPinnedBuildFile(@NotNull VirtualFile file) {
    return state.buildFileByFile.get(file.getPath());
  }

  public void pin(@NotNull VirtualFile file, @NotNull String buildFilePath) {
    state.buildFileByFile.put(file.getPath(), buildFilePath);
    HaxeResolveContextListener.publish(project);
  }

  /** Back to the automatic choice: drops the pin and the followed context alike. */
  public void unpin(@NotNull VirtualFile file) {
    boolean pinned = state.buildFileByFile.remove(file.getPath()) != null;
    boolean followed = followedBuildFileByFile.remove(file.getPath()) != null;
    if (pinned || followed) {
      HaxeResolveContextListener.publish(project);
    }
  }

  /** The build file a navigation gave the file for this session, or null. */
  @Nullable
  public String getFollowedBuildFile(@NotNull VirtualFile file) {
    return followedBuildFileByFile.get(file.getPath());
  }

  public void follow(@NotNull VirtualFile file, @NotNull String buildFilePath) {
    followedBuildFileByFile.put(file.getPath(), buildFilePath);
    HaxeResolveContextListener.publish(project);
  }
}
