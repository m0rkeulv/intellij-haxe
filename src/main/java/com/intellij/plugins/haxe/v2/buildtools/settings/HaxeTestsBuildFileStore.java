package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-container (module / project root) "tests build file": the build file that
 * supplies libs, defines, target and classpaths for the container's test runs.
 * Shares {@code .idea/haxeBuildConfig.xml} with the other build-settings stores.
 */
@State(name = "HaxeTestsBuildFiles", storages = @Storage("haxeBuildConfig.xml"))
public final class HaxeTestsBuildFileStore implements PersistentStateComponent<HaxeTestsBuildFileStore.State> {
  private final @Nullable Project project;

  public HaxeTestsBuildFileStore(@NotNull Project project) {
    this.project = project;
  }

  /** State tests exercise load/get/set without a project; no events fire then. */
  @TestOnly
  public HaxeTestsBuildFileStore() {
    this.project = null;
  }

  private void notifyChanged() {
    if (project != null) {
      project.getMessageBus().syncPublisher(HaxeBuildSettingsListener.TOPIC).buildSettingsChanged();
    }
  }


  public static final class State {
    public List<ContainerTestsFile> testsFiles = new ArrayList<>();
  }

  public static final class ContainerTestsFile {
    public String containerId;
    public String filePath;
  }

  private State state = new State();

  @NotNull
  public static HaxeTestsBuildFileStore getInstance(@NotNull Project project) {
    return project.getService(HaxeTestsBuildFileStore.class);
  }

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    if (state.testsFiles == null) {
      state.testsFiles = new ArrayList<>();
    }
    this.state = state;
    notifyChanged();
  }

  /** The container's marked tests build file path, or null when none is marked. */
  @Nullable
  public String getTestsFilePath(@NotNull String containerId) {
    return state.testsFiles.stream()
      .filter(entry -> containerId.equals(entry.containerId))
      .findFirst()
      .map(entry -> StringUtil.nullize(entry.filePath))
      .orElse(null);
  }

  /** Marks the container's tests build file; null clears the marking. */
  public void setTestsFile(@NotNull String containerId, @Nullable String filePath) {
    state.testsFiles.removeIf(entry -> containerId.equals(entry.containerId));
    if (filePath != null) {
      ContainerTestsFile entry = new ContainerTestsFile();
      entry.containerId = containerId;
      entry.filePath = filePath;
      state.testsFiles.add(entry);
    }
    notifyChanged();
  }

  /**
   * The container's tests build file among its known build files: the stored choice
   * when it still exists, otherwise the conventional candidate - a file named
   * {@code test.hxml}/{@code tests.hxml}, else the first build file living under a
   * {@code tests/} directory (candidates arrive in the tree's name order).
   */
  @Nullable
  public String resolveOrSuggest(@NotNull String containerId, @NotNull List<String> candidatePaths) {
    String stored = getTestsFilePath(containerId);
    if (stored != null && candidatePaths.contains(stored)) {
      return stored;
    }
    for (String candidate : candidatePaths) {
      if (isConventionalTestsFileName(candidate)) {
        return candidate;
      }
    }
    return candidatePaths.stream()
      .filter(HaxeTestsBuildFileStore::isUnderTestsDirectory)
      .findFirst()
      .orElse(null);
  }

  private static boolean isConventionalTestsFileName(@NotNull String path) {
    String name = PathUtil.getFileName(path).toLowerCase(Locale.ROOT);
    return name.equals("test.hxml") || name.equals("tests.hxml");
  }

  private static boolean isUnderTestsDirectory(@NotNull String path) {
    String parents = PathUtil.getParentPath(path).toLowerCase(Locale.ROOT);
    for (String segment : StringUtil.tokenize(parents.replace('\\', '/'), "/")) {
      if (segment.equals("tests")) return true;
    }
    return false;
  }
}
