package com.intellij.plugins.haxe.v2.display;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import lombok.CustomLog;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared shape of the compiler-diagnostics external annotators: gate on the
 * master switch plus the subclass's feature toggle, collect a request under
 * the read lock, fetch on the unlocked pass, then annotate every diagnostic
 * the subclass handles whose range still fits the document. Subclasses
 * contribute their toggle, their paired batch inspection, the diagnostics
 * they handle and the annotation itself.
 */
@CustomLog
abstract class HaxeCompilerDiagnosticsAnnotatorBase
  extends ExternalAnnotator<HaxeDiagnosticsFetcher.Request, List<Diagnostic>> {

  /** The per-feature toggle under the master compiler-diagnostics switch. */
  protected abstract boolean featureEnabled(@NotNull HaxeCompilerSettings settings);

  /** Whether this annotator renders the diagnostic in {@code file}. */
  protected abstract boolean handles(@NotNull PsiFile file, @NotNull Diagnostic diagnostic);

  /** Creates the annotation for a handled diagnostic at its document range. */
  protected abstract void annotate(@NotNull AnnotationHolder holder, @NotNull PsiFile file, @NotNull Document document,
                                   @NotNull Diagnostic diagnostic, @NotNull TextRange range);

  @Override
  @Nullable
  public final HaxeDiagnosticsFetcher.Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
    boolean enabled = enabled(file);
    log.debug(getClass().getSimpleName() + " pass for " + file.getName() + ": enabled=" + enabled);
    return enabled ? HaxeDiagnosticsFetcher.collect(file, editor) : null;
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

  @Override
  public final void apply(@NotNull PsiFile file, @Nullable List<Diagnostic> diagnostics, @NotNull AnnotationHolder holder) {
    if (diagnostics == null) return;
    Document document = file.getViewProvider().getDocument();
    if (document == null) return;
    int annotated = 0;
    for (Diagnostic diagnostic : diagnostics) {
      if (!handles(file, diagnostic)) continue;
      TextRange range = HaxeDiagnosticsFetcher.toTextRange(document, diagnostic.range());
      if (range == null) continue;
      annotate(holder, file, document, diagnostic, range);
      annotated++;
    }
    log.debug(getClass().getSimpleName() + " annotated " + annotated + " of " + diagnostics.size()
              + " diagnostics in " + file.getName());
  }

  private boolean enabled(@NotNull PsiFile file) {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(file.getProject());
    return settings.isCompilerDiagnosticsEnabled() && featureEnabled(settings);
  }
}
