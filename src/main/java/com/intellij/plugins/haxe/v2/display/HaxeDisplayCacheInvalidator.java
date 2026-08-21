package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeCompilationServerListener;
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
    // delivered on the EDT (see the topic contract), as the clear requires
    HaxeCompilerCaches.clearAndRehighlight(project, "haxe: compilation server state changed");
  }
}
