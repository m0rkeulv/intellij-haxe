package com.intellij.plugins.haxe.v2.display;

import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.DiagnosticKind;
import com.intellij.plugins.haxe.display.protocol.FileDiagnostics;
import com.intellij.plugins.haxe.display.protocol.Position;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Error highlighting straight from the compiler: one {@code display/diagnostics}
 * request per (debounced) editor pass, carrying the buffer when it diverges
 * from disk. Opt-in via the Haxe Compiler settings page; requires the
 * compilation server and a haxe with the JSON-RPC diagnostics method (4.3+).
 */
public class HaxeCompilerDiagnosticsAnnotator
  extends ExternalAnnotator<HaxeCompilerDiagnosticsAnnotator.Request, List<Diagnostic>> {

  /** Collected under the read lock; the network half runs on it unlocked. */
  public record Request(@NotNull HaxeCompilerDisplayService.DisplayContext context,
                        @NotNull HaxeCompilerDisplayService service,
                        @NotNull String filePath,
                        @Nullable String contents) {
  }

  @Override
  @Nullable
  public Request collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
    if (!HaxeCompilerSettings.getInstance(file.getProject()).isCompilerDiagnosticsEnabled()) return null;
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;

    // no request while the text does not parse - the compiler would choke on
    // the same syntax, and the parser's own error highlighting covers it
    if (!HaxeCompilerDisplayService.isSyntaxClean(file.getProject(), virtualFile)) return null;

    HaxeCompilerDisplayService service = HaxeCompilerDisplayService.getInstance(file.getProject());
    HaxeCompilerDisplayService.DisplayContext context = service.contextFor(virtualFile);
    if (context == null) return null;

    Document document = editor.getDocument();
    boolean diverged = FileDocumentManager.getInstance().isDocumentUnsaved(document);
    String contents = diverged ? document.getText() : null;
    return new Request(context, service, virtualFile.getPath(), contents);
  }

  @Override
  @Nullable
  public List<Diagnostic> doAnnotate(Request request) {
    List<FileDiagnostics> results =
      request.service().diagnostics(request.context(), request.filePath(), request.contents());
    if (results == null) return null;
    // the whole-project sweep is what surfaces OTHER files' errors - the
    // per-file request above tolerates broken dependencies; its findings
    // become Project view problem marks instead of editor annotations
    List<FileDiagnostics> sweep = request.service().projectDiagnostics(request.context());
    if (sweep != null) {
      HaxeCompilerProblemMarker.getInstance(request.service().getProject())
        .updateFromDiagnostics(request.filePath(), sweep);
    }
    return results.stream()
      .filter(entry -> FileUtil.pathsEqual(entry.file(), request.filePath()))
      .flatMap(entry -> entry.diagnostics().stream())
      .toList();
  }

  @Override
  public void apply(@NotNull PsiFile file, @Nullable List<Diagnostic> diagnostics, @NotNull AnnotationHolder holder) {
    if (diagnostics == null) return;
    Document document = file.getViewProvider().getDocument();
    if (document == null) return;
    for (Diagnostic diagnostic : diagnostics) {
      // inactive #if regions are already rendered by the define context - a
      // weak warning per block would only add noise
      if (diagnostic.kind() == DiagnosticKind.INACTIVE_BLOCK) continue;
      TextRange range = toTextRange(document, diagnostic);
      if (range == null) continue;
      annotate(holder, diagnostic, range);
    }
  }

  private static void annotate(@NotNull AnnotationHolder holder, @NotNull Diagnostic diagnostic, @NotNull TextRange range) {
    AnnotationBuilder builder = holder.newAnnotation(severityOf(diagnostic), messageOf(diagnostic)).range(range);
    switch (diagnostic.kind()) {
      case UNUSED_IMPORT, REMOVABLE_CODE -> builder = builder.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL);
      case UNRESOLVED_IDENTIFIER -> builder = builder.highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
      case DEPRECATION_WARNING -> builder = builder.highlightType(ProblemHighlightType.LIKE_DEPRECATED);
      default -> {
      }
    }
    builder.create();
  }

  @NotNull
  private static HighlightSeverity severityOf(@NotNull Diagnostic diagnostic) {
    return switch (diagnostic.severity()) {
      case ERROR -> HighlightSeverity.ERROR;
      case WARNING -> HighlightSeverity.WARNING;
      case INFORMATION, HINT, UNKNOWN -> HighlightSeverity.WEAK_WARNING;
    };
  }

  @NotNull
  private static String messageOf(@NotNull Diagnostic diagnostic) {
    return switch (diagnostic.kind()) {
      case UNUSED_IMPORT -> HaxeBundle.message("haxe.diagnostics.unused.import");
      case UNRESOLVED_IDENTIFIER -> HaxeBundle.message("haxe.diagnostics.unresolved.identifier");
      case COMPILER_ERROR, PARSER_ERROR, DEPRECATION_WARNING -> orGeneric(diagnostic.messageArg());
      case REMOVABLE_CODE -> orGeneric(diagnostic.args().path("description").asString(""));
      case MISSING_FIELDS -> HaxeBundle.message("haxe.diagnostics.missing.fields");
      case INACTIVE_BLOCK -> HaxeBundle.message("haxe.diagnostics.inactive.block");
      case UNKNOWN -> HaxeBundle.message("haxe.diagnostics.generic");
    };
  }

  @NotNull
  private static String orGeneric(@NotNull String message) {
    return message.isBlank() ? HaxeBundle.message("haxe.diagnostics.generic") : message;
  }

  /** Wire ranges are 0-based line/character; out-of-date positions are dropped rather than clamped wrongly. */
  @Nullable
  private static TextRange toTextRange(@NotNull Document document, @NotNull Diagnostic diagnostic) {
    Integer start = toOffset(document, diagnostic.range().start());
    Integer end = toOffset(document, diagnostic.range().end());
    if (start == null || end == null || start > end) return null;
    return new TextRange(start, end);
  }

  @Nullable
  private static Integer toOffset(@NotNull Document document, @NotNull Position position) {
    if (position.line() < 0 || position.line() >= document.getLineCount()) return null;
    int lineStart = document.getLineStartOffset(position.line());
    int lineEnd = document.getLineEndOffset(position.line());
    int offset = lineStart + position.character();
    return offset <= lineEnd ? offset : null;
  }
}
