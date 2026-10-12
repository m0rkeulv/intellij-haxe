package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.haxelib.HaxelibCacheManager;
import com.intellij.plugins.haxe.haxelib.HaxelibUtil;
import com.intellij.plugins.haxe.v2.buildtools.info.HaxeLimeProjectInfoService;
import com.intellij.plugins.haxe.v2.buildtools.info.HaxeNmeProjectInfoService;
import com.intellij.plugins.haxe.v2.buildtools.libraries.HaxeInstalledLibraries;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerCaches;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * The one list of what the build tools cache, and the three ways of dropping
 * it: one build file's evaluations (Reload on its row), everything derived
 * from the build files and the compiler (Sync), and that plus the haxelib
 * pictures (Purge). A new cache is added here, not to each command.
 */
public final class HaxeBuildCaches {
  private HaxeBuildCaches() {
  }

  /** Drops one build file's lime/nme evaluations (every target and toolchain); the next ask re-runs the tool. */
  public static void clearEvaluations(@NotNull Project project, @NotNull String buildFilePath) {
    HaxeLimeProjectInfoService.getInstance(project).invalidate(buildFilePath);
    HaxeNmeProjectInfoService.getInstance(project).invalidate(buildFilePath);
  }

  /**
   * Drops every evaluation and every compiler-derived cache (a context compiled
   * from the old configuration would keep answering otherwise), restarts
   * highlighting and announces {@link HaxeBuildEventListener#derivedCachesCleared}.
   * Call on the EDT.
   */
  public static void clearDerived(@NotNull Project project, @NotNull @NonNls String reason) {
    HaxeLimeProjectInfoService.getInstance(project).clearCache();
    HaxeNmeProjectInfoService.getInstance(project).clearCache();
    HaxeCompilerCaches.clearAndRehighlight(project, reason);
    HaxeBuildEventListener.publisher(project).derivedCachesCleared();
  }

  /** {@link #clearDerived} plus the haxelib metadata caches and the installed picture. Call on the EDT. */
  public static void purge(@NotNull Project project) {
    HaxelibUtil.clearCache();
    HaxelibCacheManager.getAllInstances().forEach(HaxelibCacheManager::reload);
    HaxeInstalledLibraries.getInstance(project).refreshAsync();
    clearDerived(project, "haxe: caches purged");
  }
}
