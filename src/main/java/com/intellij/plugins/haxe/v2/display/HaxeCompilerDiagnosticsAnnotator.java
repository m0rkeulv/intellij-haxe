package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.DiagnosticKind;
import com.intellij.plugins.haxe.display.protocol.InitializeResult;
import com.intellij.plugins.haxe.ide.annotator.semantics.HaxeSyntaxMigrationFixes;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Error highlighting straight from the compiler: one {@code display/diagnostics}
 * request per (debounced) editor pass, carrying the buffer when it diverges
 * from disk. Covers the PROBLEM kinds — compiler/parser errors, deprecation
 * warnings, unresolved identifiers, missing fields; unused imports and
 * removable code belong to their own annotators and toggles. Opt-in via the
 * Haxe Compiler settings page; requires the compilation server and a haxe
 * with the JSON-RPC diagnostics method (4.3+).
 */
public class HaxeCompilerDiagnosticsAnnotator
  extends ExternalAnnotator<HaxeDiagnosticsPass.Request, List<Diagnostic>> {

  @Override
  @Nullable
  public HaxeDiagnosticsPass.Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
    return enabled(file) ? HaxeDiagnosticsPass.collect(file, editor) : null;
  }

  /** Batch (Inspect Code) entry, reached through the paired inspection. */
  @Override
  @Nullable
  public HaxeDiagnosticsPass.Request collectInformation(@NotNull PsiFile file) {
    return enabled(file) ? HaxeDiagnosticsPass.collect(file) : null;
  }

  @Override
  public String getPairedBatchInspectionShortName() {
    return HaxeCompilerDiagnosticsBatchInspections.ERRORS_SHORT_NAME;
  }

  private static boolean enabled(@NotNull PsiFile file) {
    HaxeCompilerSettings settings = HaxeCompilerSettings.getInstance(file.getProject());
    return settings.isCompilerDiagnosticsEnabled() && settings.isDiagnosticsErrorsEnabled();
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

    HaxeLanguageLevel level = HaxeLanguageLevelUtil.getLanguageLevel(file);
    InitializeResult.SemVer haxeVersion =
      HaxeCompilerDisplayService.getInstance(file.getProject()).connectedHaxeVersion();
    for (Diagnostic diagnostic : diagnostics) {
      if (!handles(diagnostic.kind())) continue;
      if (!HaxeDiagnosticMessageFilter.shouldShow(level, haxeVersion, diagnostic)) continue;
      TextRange range = HaxeDiagnosticsPass.toTextRange(document, diagnostic.range());
      if (range == null) continue;
      annotate(holder, file, diagnostic, range, haxeVersion);
    }
  }

  /**
   * The problem kinds. Unused imports and removable code have their own
   * annotators; inactive #if regions are already rendered by the define
   * context - a weak warning per block would only add noise.
   */
  private static boolean handles(@NotNull DiagnosticKind kind) {
    return switch (kind) {
      case COMPILER_ERROR, PARSER_ERROR, DEPRECATION_WARNING, UNRESOLVED_IDENTIFIER, MISSING_FIELDS, UNKNOWN -> true;
      case UNUSED_IMPORT, REMOVABLE_CODE, INACTIVE_BLOCK -> false;
    };
  }

  private static void annotate(@NotNull AnnotationHolder holder, @NotNull PsiFile file,
                               @NotNull Diagnostic diagnostic, @NotNull TextRange range,
                               @Nullable InitializeResult.SemVer haxeVersion) {
    AnnotationBuilder builder =
      holder.newAnnotation(HaxeDiagnosticsPass.severityOf(diagnostic), messageOf(diagnostic)).range(range);
    switch (diagnostic.kind()) {
      case UNRESOLVED_IDENTIFIER -> builder = builder.highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
      case DEPRECATION_WARNING -> builder = builder.highlightType(ProblemHighlightType.LIKE_DEPRECATED);
      default -> {
      }
    }
    IntentionAction modernize = modernizeFixFor(file, diagnostic, range, haxeVersion);
    if (modernize != null) {
      builder = builder.withFix(modernize);
    }
    builder.create();
  }

  /**
   * A syntax-migration fix when the diagnostic is a deprecation pointing at a
   * construct with a known modern spelling (@:enum abstract, @:final,
   * @:extern, the renamed std APIs). Deprecation identification is
   * {@link HaxeDiagnosticMessageFilter}'s - one rule for the filter and the
   * fix offer.
   */
  @Nullable
  private static IntentionAction modernizeFixFor(@NotNull PsiFile file, @NotNull Diagnostic diagnostic,
                                                 @NotNull TextRange range,
                                                 @Nullable InitializeResult.SemVer haxeVersion) {
    if (!HaxeDiagnosticMessageFilter.isDeprecationWarning(diagnostic, haxeVersion)) return null;
    return HaxeSyntaxMigrationFixes.modernizeFixAt(file, range);
  }

  @NotNull
  private static String messageOf(@NotNull Diagnostic diagnostic) {
    return switch (diagnostic.kind()) {
      case UNRESOLVED_IDENTIFIER -> HaxeBundle.message("haxe.diagnostics.unresolved.identifier");
      case COMPILER_ERROR, PARSER_ERROR, DEPRECATION_WARNING -> orGeneric(diagnostic.messageArg());
      case MISSING_FIELDS -> HaxeBundle.message("haxe.diagnostics.missing.fields");
      default -> HaxeBundle.message("haxe.diagnostics.generic");
    };
  }

  @NotNull
  private static String orGeneric(@NotNull String message) {
    return message.isBlank() ? HaxeBundle.message("haxe.diagnostics.generic") : message;
  }
}
