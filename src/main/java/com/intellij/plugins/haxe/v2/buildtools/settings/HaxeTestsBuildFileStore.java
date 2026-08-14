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
import java.util.Objects;

/**
 * Per-container (module / project root) "tests build files": the build files
 * that supply libs, defines, target and classpaths for the container's test
 * runs. A container may hold SEVERAL — one per sub-project or framework.
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

  /** The container's marked tests build file paths (possibly none). */
  @NotNull
  public List<String> getTestsFilePaths(@NotNull String containerId) {
    return state.testsFiles.stream()
      .filter(entry -> containerId.equals(entry.containerId))
      .map(entry -> StringUtil.nullize(entry.filePath))
      .filter(Objects::nonNull)
      .toList();
  }

  /** Marks a tests build file in the container; already-marked files stay marked once. */
  public void markTestsFile(@NotNull String containerId, @NotNull String filePath) {
    boolean marked = state.testsFiles.stream()
      .anyMatch(entry -> containerId.equals(entry.containerId) && filePath.equals(entry.filePath));
    if (!marked) {
      ContainerTestsFile entry = new ContainerTestsFile();
      entry.containerId = containerId;
      entry.filePath = filePath;
      state.testsFiles.add(entry);
    }
    notifyChanged();
  }

  /** Unmarks one of the container's tests build files. */
  public void unmarkTestsFile(@NotNull String containerId, @NotNull String filePath) {
    state.testsFiles.removeIf(entry -> containerId.equals(entry.containerId) && filePath.equals(entry.filePath));
    notifyChanged();
  }

  /**
   * The container's tests build files among its known build files: the stored
   * choices that still exist, otherwise EVERY conventional candidate - files
   * named {@code test.hxml}/{@code tests.hxml} plus build files living under a
   * {@code tests/} directory (candidates arrive in the tree's name order).
   */
  @NotNull
  public List<String> resolveOrSuggestAll(@NotNull String containerId, @NotNull List<String> candidatePaths) {
    List<String> stored = getTestsFilePaths(containerId).stream()
      .filter(candidatePaths::contains)
      .toList();
    if (!stored.isEmpty()) {
      return stored;
    }
    return candidatePaths.stream()
      .filter(candidate -> isConventionalTestsFileName(candidate) || isUnderTestsDirectory(candidate))
      .toList();
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
