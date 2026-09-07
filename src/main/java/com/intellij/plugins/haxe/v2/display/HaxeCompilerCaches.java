package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * The one entry point for dropping everything derived from the compilation
 * server, and the daemon restart the services post after hydrating.
 * Callers never clear individual services: a partial clear leaves PSI-level
 * results derived from the skipped caches alive.
 */
public final class HaxeCompilerCaches {

  private HaxeCompilerCaches() {
  }

  /**
   * Clears every compiler-derived cache, then recomputes: warm-up
   * bookkeeping, blueprints, the type catalog, usage verdicts and the
   * metadata registry, followed by a PSI-cache drop and a daemon restart.
   * The group order is load-bearing — services empty first, the PSI drop
   * kills results derived from them, and the restart recomputes from
   * nothing. Call on the EDT.
   */
  public static void clearAndRehighlight(@NotNull Project project, @NotNull @NonNls String reason) {
    HaxeCompilerDisplayService.getInstance(project).resetCompiledContexts();

    HaxeCompilerTypeCatalogService.getInstance(project).clearCaches();
    HaxeCompilerMetadataService.getInstance(project).clearCache();
    HaxeCompilerResolveService.getInstance(project).clearCaches();
    HaxeCompilerUsageService.getInstance(project).clearCaches();
    HaxeGeneratedDumpService.getInstance(project).clearCaches();

    HaxeGeneratedCodePreview.clearCaches();
    HaxeDiagnosticsFetcher.clearCache(project);

    PsiManager.getInstance(project).dropPsiCaches();
    DaemonCodeAnalyzer.getInstance(project).restart(reason);
  }

  /**
   * Restarts highlighting from any thread: the restart is posted to the EDT
   * with an explicit non-modal state (a pooled-thread post without one runs
   * write-unsafe) and skipped once the project is disposed.
   */
  public static void restartHighlightingLater(@NotNull Project project, @NotNull @NonNls String reason) {
    Runnable restart = () -> DaemonCodeAnalyzer.getInstance(project).restart(reason);
    ApplicationManager.getApplication().invokeLater(restart, ModalityState.nonModal(), project.getDisposed());
  }
}
