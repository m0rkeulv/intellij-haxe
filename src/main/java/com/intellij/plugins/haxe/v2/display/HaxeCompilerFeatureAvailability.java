package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerDisplayService.DisplayContext;
import org.jetbrains.annotations.NotNull;

/**
 * Whether the compiler can serve a file, as compiler-only completion, the
 * compiler-backed Find Usages and navigation and the compiler type lookup
 * require: the project's compilation server is switched on, the file has a
 * display context, and the container of the build that context comes from
 * has a build command. The build's container is the one that matters, not
 * the file's own: a library file lives in the project-root container, which
 * never has a build command, while the build file that includes it does.
 * Without a build command the display context can still come from the
 * project's active build file, but that file alone lacks the setup the
 * command adds, so the server's answers would be of no use.
 */
final class HaxeCompilerFeatureAvailability {

  private HaxeCompilerFeatureAvailability() {
  }

  /** Call in a read action; no network. */
  static boolean isAvailable(@NotNull Project project, @NotNull VirtualFile file) {
    if (!HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled()) return false;
    DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(file);
    return context != null && hasBuildCommand(project, context.containerId());
  }

  private static boolean hasBuildCommand(@NotNull Project project, @NotNull String containerId) {
    return HaxeEnvironmentStore.getInstance(project).getCompileCommand(containerId) != null;
  }
}
