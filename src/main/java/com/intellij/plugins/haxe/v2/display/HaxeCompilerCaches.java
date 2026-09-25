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
   * Clears every compiler-derived cache (warm-up bookkeeping, type catalog,
   * metadata registry, blueprints, usage verdicts, dumps, preview files and
   * diagnostics), then drops the PSI caches and restarts the daemon. The
   * order matters: the services empty first, the PSI drop removes results
   * derived from them, and the restart recomputes from scratch. Call on the
   * EDT.
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
   * Restarts highlighting from any thread. The restart is posted to the EDT
   * with an explicit non-modal state (a post from a pooled thread without one
   * runs write-unsafe) and skipped once the project is disposed.
   */
  public static void restartHighlightingLater(@NotNull Project project, @NotNull @NonNls String reason) {
    Runnable restart = () -> DaemonCodeAnalyzer.getInstance(project).restart(reason);
    ApplicationManager.getApplication().invokeLater(restart, ModalityState.nonModal(), project.getDisposed());
  }
}
