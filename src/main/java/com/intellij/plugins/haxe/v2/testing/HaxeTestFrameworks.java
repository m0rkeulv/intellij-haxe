package com.intellij.plugins.haxe.v2.testing;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The frameworks the plugin knows, in detection order: the first whose
 * haxelib a tests build declares wins, utest is the default. The run planner
 * dispatches compile arguments through this list, the test locator dispatches
 * result navigation through it, and the gutter markers detect through it.
 */
public final class HaxeTestFrameworks {

  public static final List<HaxeTestFramework> ALL =
    List.of(new MunitFramework(), new BuddyFramework(), new TinkFramework(), new UtestFramework());

  private HaxeTestFrameworks() {
  }

  /** The tests build's framework, from the SELECTED section's -lib declarations. Call inside a read action. */
  @NotNull
  public static HaxeTestFramework forBuildFile(@NotNull Project project, @NotNull String buildFilePath) {
    HaxeTestFramework detected = detectedFrameworkFor(project, buildFilePath);
    return detected != null ? detected : ALL.get(ALL.size() - 1);
  }

  /**
   * Like {@link #forBuildFile}, but null when the build declares NO known
   * framework. The offer surfaces (tests rows, marking, gutter candidates)
   * gate on this, so a plain application build never presents a test run;
   * actual runs keep the utest default for configurations that predate the
   * gate. A framework reachable only TRANSITIVELY (a custom lib depending on
   * utest) is not seen - the tests build declares its framework directly.
   * Call inside a read action.
   */
  @Nullable
  public static HaxeTestFramework detectedFrameworkFor(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    HaxeBuildFileType type = file == null || !file.isValid() ? null : HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return null;
    return detectedFramework(HaxeBuildSections.inspectSelected(project, new HaxeBuildFile(file, type)).libraries());
  }

  /** The framework the library list declares, or null when none of the known frameworks is among them. */
  @Nullable
  public static HaxeTestFramework detectedFramework(@NotNull List<HaxeBuildFileInfo.HaxeLibDependency> libraries) {
    for (HaxeTestFramework framework : ALL) {
      boolean declared = libraries.stream()
        .anyMatch(library -> library.name().equalsIgnoreCase(framework.libraryName()));
      if (declared) return framework;
    }
    return null;
  }
}
