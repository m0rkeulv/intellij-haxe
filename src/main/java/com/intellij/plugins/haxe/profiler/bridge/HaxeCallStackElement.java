package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.ide.util.EditSourceUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.profiler.model.StackFrame;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.pom.Navigatable;
import com.intellij.profiler.api.BaseCallStackElement;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * One frame of a profiled Haxe stack shown in the IU profiler views. The
 * symbol is the target's qualified name ({@code pack.Class.method});
 * jump-to-source resolves it against the project's Haxe classes — the
 * longest symbol prefix that names a class wins, so a closure symbol
 * ({@code Main.update.closure}) still lands on its enclosing method.
 */
public final class HaxeCallStackElement extends BaseCallStackElement {

  private final String symbol;
  private final @Nullable String file;
  private final int line;

  public HaxeCallStackElement(@NotNull String symbol, @Nullable String file, int line) {
    this.symbol = symbol;
    this.file = file;
    this.line = line;
  }

  @Override
  public @NotNull String fullName() {
    return symbol;
  }

  @Override
  public boolean isNavigatable() {
    return symbol.indexOf('.') > 0;
  }

  @Override
  public NavigatablePsiElement @NotNull [] calcNavigatables(@NotNull Project project) {
    return ReadAction.compute(() -> resolve(project));
  }

  /**
   * Opens the frame's source from a UI event. The PSI work (resolve and
   * descriptor building) runs under a read action — a raw EDT mouse event
   * carries no implicit read access; the descriptor then navigates PSI-free.
   */
  static void navigateToFrame(@NotNull Project project, @NotNull StackFrame frame) {
    HaxeCallStackElement element = new HaxeCallStackElement(frame.symbol(), frame.file(), frame.line());
    Navigatable descriptor = ReadAction.compute(() -> element.descriptor(project));
    if (descriptor != null) {
      descriptor.navigate(true);
    }
  }

  @Nullable
  private Navigatable descriptor(Project project) {
    NavigatablePsiElement[] navigatables = calcNavigatables(project);
    return navigatables.length > 0 ? EditSourceUtil.getDescriptor(navigatables[0]) : null;
  }

  private NavigatablePsiElement @NotNull [] resolve(Project project) {
    PsiManager psiManager = PsiManager.getInstance(project);
    GlobalSearchScope scope = GlobalSearchScope.allScope(project);
    String[] segments = symbol.split("\\.");
    for (int memberIndex = segments.length - 1; memberIndex >= 1; memberIndex--) {
      String className = String.join(".", List.of(segments).subList(0, memberIndex));
      HaxeClass haxeClass = HaxeResolveUtil.findClassByQName(className, psiManager, scope);
      if (haxeClass == null) continue;
      NavigatablePsiElement member = findMember(haxeClass, segments[memberIndex]);
      return new NavigatablePsiElement[]{member != null ? member : haxeClass};
    }
    return NavigatablePsiElement.EMPTY_NAVIGATABLE_ELEMENT_ARRAY;
  }

  @Nullable
  private static NavigatablePsiElement findMember(HaxeClass haxeClass, String name) {
    List<HaxeNamedComponent> methods = haxeClass.findHaxeMethodByName(name, null);
    if (!methods.isEmpty()) return methods.getFirst();
    return haxeClass.findHaxeFieldByName(name, null);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof HaxeCallStackElement element)) return false;
    return line == element.line && symbol.equals(element.symbol) && Objects.equals(file, element.file);
  }

  @Override
  public int hashCode() {
    return Objects.hash(symbol, file, line);
  }
}
