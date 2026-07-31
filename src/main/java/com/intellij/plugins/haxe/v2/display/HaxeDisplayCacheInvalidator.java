package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerListener;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

/**
 * Every compiler-derived cache dies with the server: a restarted server has
 * an EMPTY module cache, so blueprints, usage verdicts, warm-up bookkeeping
 * and the metadata registry all describe a process that no longer exists.
 * The PSI-cache drop clears resolve results computed while the old server
 * (or a broken compile) made members look unresolved.
 */
public class HaxeDisplayCacheInvalidator implements HaxeCompilationServerListener {

  private final Project project;

  public HaxeDisplayCacheInvalidator(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void serverStateChanged() {
    HaxeCompilerResolveService.getInstance(project).clearCaches();
    HaxeCompilerUsageService.getInstance(project).clearCaches();
    HaxeCompilerMetadataService.getInstance(project).clearCache();
    // delivered on the EDT (see the topic contract) - safe for both calls
    PsiManager.getInstance(project).dropPsiCaches();
    DaemonCodeAnalyzer.getInstance(project).restart();
  }
}
