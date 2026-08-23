package com.intellij.plugins.haxe.v2.display;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.FileDiagnostics;
import com.intellij.plugins.haxe.display.protocol.Position;
import com.intellij.plugins.haxe.display.protocol.Range;
import com.intellij.psi.PsiFile;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shared half of the compiler-diagnostics annotators: request gating,
 * the {@code display/diagnostics} fetch, and wire-range conversion. Every
 * per-feature annotator (errors, unused imports, removable code) collects
 * through {@link #collect} and fetches through {@link #fetch}.
 *
 * The cache keeps the LAST KNOWN diagnostics per file. Within the TTL it
 * also means one editor pass costs ONE wire request no matter how many
 * annotators consume it — but its real job is surviving transient fetch
 * failures (server restarting, one refused socket): a settings-driven
 * re-highlight then still renders the last known diagnostics instead of
 * silently wiping them until the next edit.
 */
final class HaxeDiagnosticsFetcher {

  /** Collected under the read lock; the network half runs on it unlocked. */
  record Request(@NotNull HaxeCompilerDisplayService.DisplayContext context,
                 @NotNull HaxeCompilerDisplayService service,
                 @NotNull String filePath,
                 @Nullable String contents) {
  }

  private record CacheEntry(int contentsHash, long timestampMillis, List<Diagnostic> diagnostics) {
  }

  /**
   * Last known diagnostics per file path, one map per PROJECT (a file open in
   * two projects must not render the other project's diagnostics, and a
   * per-project cache clear must not wipe every project). Entries persist
   * past the TTL as the transient-failure fallback; the service dies with
   * its project.
   */
  @Service(Service.Level.PROJECT)
  static final class Cache {
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @NotNull
    static Cache getInstance(@NotNull Project project) {
      return project.getService(Cache.class);
    }
  }

  /** Long enough to span one daemon pass over all annotators, short enough to never serve a stale edit. */
  private static final long CACHE_TTL_MILLIS = 5_000;

  private static final int CACHE_MAX_FILES = 200;

  private HaxeDiagnosticsFetcher() {
  }

  /** The request when compiler diagnostics can run for this file, else null (feature toggles are the caller's gate). */
  @Nullable
  static Request collect(@NotNull PsiFile file, @NotNull Editor editor) {
    return collect(file, editor.getDocument());
  }

  /** Batch (Inspect Code) entry: no editor; the file's document still decides whether unsaved contents ride along. */
  @Nullable
  static Request collect(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null) return null;
    Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    return document == null ? null : collect(file, document);
  }

  @Nullable
  private static Request collect(@NotNull PsiFile file, @NotNull Document document) {
    VirtualFile virtualFile = file.getVirtualFile();
    if (virtualFile == null || !virtualFile.isInLocalFileSystem()) return null;

    // no request while the text does not parse - the compiler would choke on
    // the same syntax, and the parser's own error highlighting covers it
    if (!HaxeCompilerDisplayService.isSyntaxClean(file.getProject(), virtualFile)) return null;

    HaxeCompilerDisplayService service = HaxeCompilerDisplayService.getInstance(file.getProject());
    HaxeCompilerDisplayService.DisplayContext context = service.contextFor(virtualFile);
    if (context == null) return null;

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
    Map<String, CacheEntry> cache = Cache.getInstance(request.service().getProject()).entries;
    String key = request.filePath();
    int contentsHash = request.contents() != null ? request.contents().hashCode() : 0;
    CacheEntry cached = cache.get(key);
    boolean fresh = cached != null && cached.contentsHash() == contentsHash
                    && System.currentTimeMillis() - cached.timestampMillis() < CACHE_TTL_MILLIS;
    if (fresh) {
      return cached.diagnostics();
    }

    List<FileDiagnostics> results =
      request.service().diagnostics(request.context(), request.filePath(), request.contents());
    if (results == null) {
      // transient failure (server restarting, one refused socket): keep
      // rendering the last known diagnostics rather than wiping highlights.
      // Out-of-date ranges drop in toTextRange; the quick fixes re-validate
      // the captured text before touching the document.
      return cached != null ? cached.diagnostics() : null;
    }

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
    if (cache.size() >= CACHE_MAX_FILES) {
      evictOldest(cache);
    }
    cache.put(key, new CacheEntry(contentsHash, System.currentTimeMillis(), diagnostics));
    return diagnostics;
  }

  static void clearCache(@NotNull Project project) {
    Cache.getInstance(project).entries.clear();
  }

  private static void evictOldest(@NotNull Map<String, CacheEntry> cache) {
    cache.entrySet().stream()
      .min(Map.Entry.comparingByValue(Comparator.comparingLong(CacheEntry::timestampMillis)))
      .ifPresent(oldest -> cache.remove(oldest.getKey()));
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
