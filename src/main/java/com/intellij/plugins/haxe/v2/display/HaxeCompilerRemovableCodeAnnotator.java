package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.DiagnosticKind;
import com.intellij.plugins.haxe.display.protocol.Range;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Removable code straight from the compiler's {@code display/diagnostics}
 * (unused pattern variables and similar spans the typer proves dead), with a
 * quick fix taking the args' removal range — which may be wider than the
 * highlighted span. Haxe 5 may supply a {@code newCode} replacement; the fix
 * then replaces instead of deleting. While its toggle is on it REPLACES the
 * plugin's unused field/function/local-var inspections (they gate themselves
 * off).
 */
public class HaxeCompilerRemovableCodeAnnotator extends HaxeCompilerDiagnosticsAnnotatorBase {

  @Override
  protected boolean featureEnabled(@NotNull HaxeCompilerSettings settings) {
    return settings.isDiagnosticsRemovableCodeEnabled();
  }

  @Override
  public String getPairedBatchInspectionShortName() {
    return HaxeCompilerDiagnosticsBatchInspections.REMOVABLE_CODE_SHORT_NAME;
  }

  @Override
  public void apply(@NotNull PsiFile file, @Nullable List<Diagnostic> diagnostics, @NotNull AnnotationHolder holder) {
    if (diagnostics == null) return;
    Document document = file.getViewProvider().getDocument();
    if (document == null) return;
    for (Diagnostic diagnostic : diagnostics) {
      if (diagnostic.kind() != DiagnosticKind.REMOVABLE_CODE) continue;
      TextRange range = HaxeDiagnosticsFetcher.toTextRange(document, diagnostic.range());
      if (range == null) continue;

      String message = messageOf(diagnostic);
      var builder = holder.newAnnotation(HaxeDiagnosticsFetcher.severityOf(diagnostic), message)
        .range(range)
        .highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL);
      TextRange removal = removalRange(document, diagnostic, range);
      if (removal != null) {
        builder = builder.withFix(fixFor(diagnostic, document, removal));
      }
      builder.create();
    }
  }

  @NotNull
  private static HaxeReplaceRangeQuickFix fixFor(@NotNull Diagnostic diagnostic, @NotNull Document document,
                                                 @NotNull TextRange removal) {
    String newCode = diagnostic.newCodeArg();
    String label = newCode != null
                   ? HaxeBundle.message("haxe.diagnostics.fix.replace.code")
                   : HaxeBundle.message("haxe.diagnostics.fix.remove.code");
    return new HaxeReplaceRangeQuickFix(label, removal, document.getText(removal), newCode != null ? newCode : "");
  }

  @NotNull
  private static String messageOf(@NotNull Diagnostic diagnostic) {
    String description = diagnostic.descriptionArg();
    return description.isBlank() ? HaxeBundle.message("haxe.diagnostics.generic") : description;
  }

  /** The args' removal span when the compiler supplies one, else the highlighted span. */
  @Nullable
  private static TextRange removalRange(@NotNull Document document, @NotNull Diagnostic diagnostic,
                                        @NotNull TextRange highlighted) {
    Range fromArgs = diagnostic.removableRangeArg();
    if (fromArgs == null) return highlighted;
    TextRange converted = HaxeDiagnosticsFetcher.toTextRange(document, fromArgs);
    return converted != null ? converted : highlighted;
  }
}
