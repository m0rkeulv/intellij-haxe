package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.FindReferencesKind;
import com.intellij.plugins.haxe.display.protocol.Location;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.lang.psi.HaxeComponentName;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.display.HaxeUsageSearch.UsageState;
import com.intellij.psi.PsiFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The compiler's answer to "is this member referenced anywhere?" via
 * {@code display/references} — it sees the post-macro program, so usages that
 * exist only in generated code (a {@code @:bind}-wired handler) count.
 *
 * Inspections run under the read lock, so queries are strictly cache-only:
 * a miss schedules background hydration and answers UNKNOWN this once; the
 * daemon restart after the verdict lands re-runs the inspection against the
 * cache. Verdicts are per file revision — an edit anywhere in the file drops
 * them (offsets move); usages appearing in OTHER files leave a stale verdict
 * until then, which the asymmetric consumers tolerate (a stale USED merely
 * keeps suppressing a hint).
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerUsageService {

  private record UsageKey(@NotNull String contextKey, @NotNull String filePath, @NotNull String memberName, int offset) {
  }

  private record Verdict(long fileStamp, @NotNull UsageState state) {
  }

  private record Request(@NotNull UsageKey key,
                         @NotNull HaxeCompilerDisplayService.DisplayContext context,
                         @Nullable String contents,
                         @NotNull FindReferencesKind kind,
                         long fileStamp) {
  }

  private static final long FAILURE_COOLDOWN_MS = 30_000;

  private final Project project;
  private final Map<UsageKey, Verdict> verdicts = new ConcurrentHashMap<>();
  private final Set<UsageKey> hydrating = ConcurrentHashMap.newKeySet();
  private final Map<UsageKey, Long> failedAt = new ConcurrentHashMap<>();

  public HaxeCompilerUsageService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerUsageService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerUsageService.class);
  }

  /**
   * Cache-only usage verdict for a declaration. UNKNOWN when the feature is
   * off, the file has no display context, or the answer is still being
   * fetched. Call in a read action.
   */
  @NotNull
  public UsageState usageState(@NotNull HaxeNamedComponent declaration) {
    if (!HaxeCompilerSettings.getInstance(project).isCompilerDiagnosticsEnabled()) return UsageState.UNKNOWN;
    if (DumbService.isDumb(project)) return UsageState.UNKNOWN;
    HaxeComponentName componentName = declaration.getComponentName();
    if (componentName == null) return UsageState.UNKNOWN;
    PsiFile file = declaration.getContainingFile();
    if (file == null) return UsageState.UNKNOWN;
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    if (virtualFile == null) return UsageState.UNKNOWN;
    HaxeCompilerDisplayService.DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(virtualFile);
    if (context == null) return UsageState.UNKNOWN;

    long fileStamp = virtualFile.getModificationStamp();
    UsageKey key = new UsageKey(HaxeCompilerDisplayService.contextKey(context),
                                virtualFile.getPath(),
                                componentName.getText(),
                                componentName.getTextRange().getStartOffset());
    Verdict verdict = verdicts.get(key);
    if (verdict != null && verdict.fileStamp() == fileStamp) {
      return verdict.state();
    }

    // no request while the text does not parse - cached verdicts above are
    // still served, only new server work waits for valid syntax
    if (!HaxeCompilerDisplayService.isSyntaxClean(project, virtualFile)) return UsageState.UNKNOWN;

    // overriding methods can be reached through a base-typed call
    FindReferencesKind kind = declaration instanceof HaxeMethod
                              ? FindReferencesKind.WITH_BASE_AND_DESCENDANTS
                              : FindReferencesKind.DIRECT;
    scheduleHydration(new Request(key, context, unsavedContents(virtualFile), kind, fileStamp));
    return UsageState.UNKNOWN;
  }

  public void clearCaches() {
    verdicts.clear();
    failedAt.clear();
  }

  @Nullable
  private static String unsavedContents(@NotNull VirtualFile virtualFile) {
    FileDocumentManager documents = FileDocumentManager.getInstance();
    Document document = documents.getCachedDocument(virtualFile);
    return document != null && documents.isDocumentUnsaved(document) ? document.getText() : null;
  }

  private void scheduleHydration(@NotNull Request request) {
    Long failed = failedAt.get(request.key());
    if (failed != null && System.currentTimeMillis() - failed < FAILURE_COOLDOWN_MS) return;
    if (!hydrating.add(request.key())) return;

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        UsageState state = fetchVerdict(request);
        if (state != UsageState.UNKNOWN) {
          verdicts.put(request.key(), new Verdict(request.fileStamp(), state));
          failedAt.remove(request.key());
          HaxeCompilerCaches.restartHighlightingLater(project, "haxe: compiler usage data updated");
        } else {
          failedAt.put(request.key(), System.currentTimeMillis());
        }
      } catch (Throwable t) {
        log.warn("usage lookup failed for " + request.key().memberName() + ": " + t.getMessage());
        failedAt.put(request.key(), System.currentTimeMillis());
      } finally {
        hydrating.remove(request.key());
      }
    });
  }

  @NotNull
  private UsageState fetchVerdict(@NotNull Request request) {
    HaxeCompilerDisplayService.Connected connected =
      HaxeCompilerDisplayService.getInstance(project).connectFor(request.context(), DisplayMethods.FIND_REFERENCES);
    if (connected == null) return UsageState.UNKNOWN;
    try {
      String filePath = request.key().filePath();
      if (request.contents() != null) {
        connected.client().invalidate(connected.args(), filePath);
      }
      List<Location> references = connected.client()
        .references(connected.args(), filePath, request.key().offset(), request.contents(), request.kind());
      return references.isEmpty() ? UsageState.UNUSED : UsageState.USED;
    } catch (DisplayRequestException e) {
      log.info("display/references failed for " + request.key().memberName() + ": " + e.getMessage());
      return UsageState.UNKNOWN;
    }
  }
}
