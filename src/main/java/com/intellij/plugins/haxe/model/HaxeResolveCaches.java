package com.intellij.plugins.haxe.model;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.HaxeClassResolveCache;
import com.intellij.plugins.haxe.lang.psi.HaxeFileTypeResolverCacheService;
import com.intellij.plugins.haxe.model.evaluator.HaxeCallExpressionEvaluatorCacheService;
import com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluatorCacheService;
import com.intellij.plugins.haxe.model.evaluator.HaxeUntypedParameterBindingCache;
import org.jetbrains.annotations.NotNull;

/**
 * The single entry point for clearing the plugin's own resolution caches; a
 * partial clear keeps results derived from the skipped caches alive. Every
 * PSI change ({@link HaxeResolveCachePsiChangeListener}) and every roots
 * change ({@link HaxeResolveCacheRootsListener}) clears them.
 */
public final class HaxeResolveCaches {

  private HaxeResolveCaches() {
  }

  public static void clear(@NotNull Project project) {
    HaxeClassResolveCache.getInstance(project).clear();
    HaxeFileTypeResolverCacheService.getInstance(project).invalidate();
    project.getService(HaxeExpressionEvaluatorCacheService.class).clearCaches();
    project.getService(HaxeCallExpressionEvaluatorCacheService.class).clearCaches();
    HaxeFilenameCandidateCache.getInstance(project).clearCaches();
    HaxeUntypedParameterBindingCache.getInstance(project).clearCaches();
  }
}
