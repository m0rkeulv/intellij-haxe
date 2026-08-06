package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.metadata.psi.HaxeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataCompileTimeMeta;
import com.intellij.plugins.haxe.metadata.psi.HaxeMetadataType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Unified "is this declaration used?" over static reference search and the
 * compiler's post-macro knowledge. Static search runs first and stops at the
 * FIRST hit; only a statically-unreferenced member falls through to the
 * compiler side ({@link HaxeCompilerUsageService}), which sees usages that
 * exist only in generated code.
 *
 * The answer is deliberately tri-state: the compiler half is cache-only under
 * the read lock, so "no reference found" splits into UNUSED (compiler
 * confirmed) and UNKNOWN (verdict still being fetched — the caller's static
 * conclusion stands for this pass and self-corrects on the next).
 */
public final class HaxeUsageSearch {

  public enum UsageState {
    /** A reference exists — found statically or by the compiler. */
    USED,
    /** The compiler confirmed there are no references, generated code included. */
    UNUSED,
    /** No static reference, and no compiler verdict (yet) — treat per the caller's static conclusion. */
    UNKNOWN
  }

  // TODO: processReferences(declaration, processor) - full merged enumeration
  //  (static + compiler locations, deduped) for Find Usages / safe delete;
  //  may block on the network, so it belongs on a progress thread.

  private HaxeUsageSearch() {
  }

  /** Call in a read action; never blocks on the network. */
  @NotNull
  public static UsageState usageState(@NotNull HaxeNamedComponent declaration) {
    return usageState(declaration, GlobalSearchScope.projectScope(declaration.getProject()));
  }

  /** As above with a caller-chosen static search scope (locals search their enclosing scope only). */
  @NotNull
  public static UsageState usageState(@NotNull HaxeNamedComponent declaration, @NotNull SearchScope scope) {
    if (ReferencesSearch.search(declaration, scope, false).findFirst() != null) {
      return UsageState.USED;
    }
    return HaxeCompilerUsageService.getInstance(declaration.getProject()).usageState(declaration);
  }

  /**
   * Whether the declaration's metadata should exempt it from "unused"
   * warnings. Registry-known metadata (except {@code @:deprecated}) may be
   * consumed invisibly — even the compiler's reference search reports zero
   * usages for haxeui's {@code @:bind} handlers, yet they run. Metadata the
   * registry does NOT know is likely a typo and keeps no one alive; a user
   * whose custom meta is misjudged adds {@code @:keep}. While the registry
   * has not loaded, any metadata counts (conservative).
   */
  public static boolean metadataKeepsAlive(@NotNull HaxeNamedComponent declaration) {
    List<String> names = new ArrayList<>();
    for (HaxeMeta meta : declaration.getMetadataList(HaxeMetadataCompileTimeMeta.class)) {
      HaxeMetadataType type = meta.getType();
      if (type != null) {
        names.add(type.getText());
      }
    }
    // deprecation documents a member, it does not use it
    names.remove("deprecated");
    if (names.isEmpty()) return false;

    PsiFile file = declaration.getContainingFile();
    VirtualFile virtualFile = file != null ? file.getOriginalFile().getVirtualFile() : null;
    if (virtualFile == null) return true;
    Set<String> known = HaxeCompilerMetadataService.getInstance(declaration.getProject()).knownBareNames(virtualFile);
    if (known == null) return true;
    return names.stream().anyMatch(known::contains);
  }
}
