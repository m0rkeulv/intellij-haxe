package com.intellij.plugins.haxe.v2.display;

import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Editor;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared shape of the compiler-diagnostics external annotators: gate on the
 * master switch plus the subclass's feature toggle, collect a request under
 * the read lock, fetch on the unlocked pass. Subclasses contribute their
 * toggle, their paired batch inspection and {@code apply}.
 */
abstract class HaxeCompilerDiagnosticsAnnotatorBase
  extends ExternalAnnotator<HaxeDiagnosticsFetcher.Request, List<Diagnostic>> {

  /** The per-feature toggle under the master compiler-diagnostics switch. */
  protected abstract boolean featureEnabled(@NotNull HaxeCompilerSettings settings);

  @Override
  @Nullable
  public final HaxeDiagnosticsFetcher.Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
    return enabled(file) ? HaxeDiagnosticsFetcher.collect(file, editor) : null;
  }

  /** Batch (Inspect Code) entry, reached through the paired inspection. */
  @Override
  @Nullable
  public final HaxeDiagnosticsFetcher.Request collectInformation(@NotNull PsiFile file) {
    return enabled(file) ? HaxeDiagnosticsFetcher.collect(file) : null;
  }

  @Override
  @Nullable
  public final List<Diagnostic> doAnnotate(HaxeDiagnosticsFetcher.Request request) {
    return HaxeDiagnosticsFetcher.fetch(request);
  }

  private boolean enabled(@NotNull PsiFile file) {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(file.getProject());
    return settings.isCompilerDiagnosticsEnabled() && featureEnabled(settings);
  }
}
