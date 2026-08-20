package com.intellij.plugins.haxe.v2.display;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.FileDiagnostics;
import com.intellij.plugins.haxe.display.protocol.Position;
import com.intellij.plugins.haxe.display.protocol.Range;
import com.intellij.psi.PsiFile;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shared half of the compiler-diagnostics annotators: request gating,
 * the {@code display/diagnostics} fetch, and wire-range conversion. Every
 * per-feature annotator (errors, unused imports, removable code) collects
 * through {@link #collect} and fetches through {@link #fetch}; a short-lived
 * cache keyed by file + buffer state means one editor pass costs ONE wire
 * request no matter how many annotators consume it.
 */
final class HaxeDiagnosticsPass {

  /** Collected under the read lock; the network half runs on it unlocked. */
  record Request(@NotNull HaxeCompilerDisplayService.DisplayContext context,
                 @NotNull HaxeCompilerDisplayService service,
                 @NotNull String filePath,
                 @Nullable String contents) {
  }

  private record CacheKey(String filePath, int contentsHash) {
  }

  private record CacheEntry(long timestampMillis, List<Diagnostic> diagnostics) {
  }

  /** Long enough to span one daemon pass over all annotators, short enough to never serve a stale edit. */
  private static final long CACHE_TTL_MILLIS = 5_000;

  private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();

  private HaxeDiagnosticsPass() {
  }

  /** The request when compiler diagnostics can run for this file, else null (feature toggles are the caller's gate). */
  @Nullable
  static Request collect(@NotNull PsiFile file, @NotNull Editor editor) {
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

  /**
   * This file's diagnostics, fetched once per (file, buffer state) and shared
   * across the annotators of one pass. The whole-project sweep feeding the
   * Project view problem marks rides along on the cache-filling call only.
   */
  @Nullable
  static List<Diagnostic> fetch(@NotNull Request request) {
    CacheKey key = new CacheKey(request.filePath(),
                                request.contents() != null ? request.contents().hashCode() : 0);
    CacheEntry cached = CACHE.get(key);
    if (cached != null && System.currentTimeMillis() - cached.timestampMillis() < CACHE_TTL_MILLIS) {
      return cached.diagnostics();
    }

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

    List<Diagnostic> diagnostics = results.stream()
      .filter(entry -> FileUtil.pathsEqual(entry.file(), request.filePath()))
      .flatMap(entry -> entry.diagnostics().stream())
      .toList();
    CACHE.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().timestampMillis() >= CACHE_TTL_MILLIS);
    CACHE.put(key, new CacheEntry(System.currentTimeMillis(), diagnostics));
    return diagnostics;
  }

  @NotNull
  static HighlightSeverity severityOf(@NotNull Diagnostic diagnostic) {
    return switch (diagnostic.severity()) {
      case ERROR -> HighlightSeverity.ERROR;
      case WARNING -> HighlightSeverity.WARNING;
      case INFORMATION, HINT, UNKNOWN -> HighlightSeverity.WEAK_WARNING;
    };
  }

  /** Wire ranges are 0-based line/character; out-of-date positions are dropped rather than clamped wrongly. */
  @Nullable
  static TextRange toTextRange(@NotNull Document document, @NotNull Range range) {
    Integer start = toOffset(document, range.start());
    Integer end = toOffset(document, range.end());
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
