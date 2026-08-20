package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.DiagnosticKind;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Unused imports straight from the compiler's {@code display/diagnostics},
 * with a remove-the-import quick fix from the reported range. While its
 * toggle is on it REPLACES the plugin's own unused-import inspection (which
 * gates itself off), so the compiler's post-macro view is the single source
 * of truth for what an import is worth.
 */
public class HaxeCompilerUnusedImportAnnotator
  extends ExternalAnnotator<HaxeDiagnosticsPass.Request, List<Diagnostic>> {

  @Override
  @Nullable
  public HaxeDiagnosticsPass.Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(file.getProject());
    if (!settings.isCompilerDiagnosticsEnabled() || !settings.isDiagnosticsUnusedImportsEnabled()) return null;
    return HaxeDiagnosticsPass.collect(file, editor);
  }

  @Override
  @Nullable
  public List<Diagnostic> doAnnotate(HaxeDiagnosticsPass.Request request) {
    return HaxeDiagnosticsPass.fetch(request);
  }

  @Override
  public void apply(@NotNull PsiFile file, @Nullable List<Diagnostic> diagnostics, @NotNull AnnotationHolder holder) {
    if (diagnostics == null) return;
    Document document = file.getViewProvider().getDocument();
    if (document == null) return;
    for (Diagnostic diagnostic : diagnostics) {
      if (diagnostic.kind() != DiagnosticKind.UNUSED_IMPORT) continue;
      TextRange range = HaxeDiagnosticsPass.toTextRange(document, diagnostic.range());
      if (range == null) continue;

      HaxeReplaceRangeQuickFix fix = new HaxeReplaceRangeQuickFix(
        HaxeBundle.message("haxe.diagnostics.fix.remove.import"), range, document.getText(range), "");
      holder.newAnnotation(HaxeDiagnosticsPass.severityOf(diagnostic), HaxeBundle.message("haxe.diagnostics.unused.import"))
        .range(range)
        .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
        .withFix(fix)
        .create();
    }
  }
}
