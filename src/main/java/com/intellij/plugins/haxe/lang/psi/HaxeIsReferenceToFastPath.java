package com.intellij.plugins.haxe.lang.psi;

import com.intellij.plugins.haxe.lang.psi.impl.HaxeTypeParameterDeclaration;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.plugins.haxe.lang.psi.HaxeResolveChecks.*;

/**
 * Candidate-directed shortcut for isReferenceTo. A local-scoped target can
 * only be produced by the tree-walk check family, and Haxe's resolution
 * order ranks locals above everything except keywords, so the answer never
 * needs the chain / enum-hint / element-usage checks or the ResolveCache
 * machinery. The one position where that ranking inverts is a switch-case
 * pattern (an enum constructor beats a capture variable there), which is
 * deferred to the full pipeline. See doc/isreferenceto-restricted-resolve.md.
 *
 * Runs OUTSIDE the resolver frames and writes nothing into any cache: the
 * answer is candidate-relative, not the reference's general resolution.
 */
public final class HaxeIsReferenceToFastPath {

  private HaxeIsReferenceToFastPath() {
  }

  /** Candidate kinds only the tree-walk check family can resolve to. */
  public static boolean isLocalScopedTarget(@NotNull HaxeComponentName componentName) {
    PsiElement declaration = componentName.getParent();
    return declaration instanceof HaxeParameter
           || declaration instanceof HaxeLocalVarDeclaration
           || declaration instanceof HaxeLocalFunctionDeclaration
           || declaration instanceof HaxeSwitchCaseCaptureVar
           || declaration instanceof HaxeEnumExtractedValue
           || declaration instanceof HaxeTypeParameterDeclaration;
  }

  /**
   * @return true/false when the fast path can answer; null when the
   * occurrence needs the full pipeline (switch-case pattern position).
   */
  public static @Nullable Boolean tryIsReferenceTo(@NotNull HaxeReference reference, @NotNull HaxeComponentName target) {
    // in a case pattern an identifier naming an enum constructor IS the
    // constructor; capture-variable reading only applies when none matches
    if (inSwitchCasePatternPosition(reference)) return null;

    // locals are never accessed as expr.name
    if (isMemberPartOfQualifiedChain(reference)) return Boolean.FALSE;

    // a local is only visible inside the scope owning its declaration
    PsiElement scope = declaringScope(target);
    if (scope == null || !PsiTreeUtil.isAncestor(scope, reference, false)) return Boolean.FALSE;

    // the tree-walk family in pipeline order; the nearest declaration wins,
    // which also settles shadowing
    List<? extends PsiElement> result = checkIsTypeParameter(reference);
    if (result == null) result = checkEnumExtractor(reference);
    if (result == null) result = checkReferenceInExtractorMatchExpression(reference);
    if (result == null) result = checkIsSwitchVar(reference);
    if (result == null) result = checkCaptureVarReference(reference);
    if (result == null) result = checkByTreeWalk(reference, scope);
    if (result == null) result = checkCaptureVar(reference);

    if (result == null || result.isEmpty()) return Boolean.FALSE;
    return resolvesTo(result.get(0), target);
  }

  private static boolean inSwitchCasePatternPosition(HaxeReference reference) {
    PsiElement parent = reference.getParent();
    PsiElement positionParent = parent instanceof HaxeCallExpression ? parent.getParent() : parent;
    return positionParent instanceof HaxeEnumValueReference;
  }

  private static boolean isMemberPartOfQualifiedChain(HaxeReference reference) {
    if (!(reference.getParent() instanceof HaxeReferenceExpression parentExpression)) return false;
    HaxeReference[] parts = PsiTreeUtil.getChildrenOfType(parentExpression, HaxeReference.class);
    return parts != null && parts.length >= 2 && parts[0] != reference;
  }

  /**
   * The element whose subtree bounds the candidate's visibility: nearest
   * enclosing function for parameters and locals, enclosing class for class
   * type parameters. Conservative (a larger scope only costs pruning, never
   * correctness - the tree walk still decides).
   */
  private static @Nullable PsiElement declaringScope(HaxeComponentName target) {
    PsiElement declaration = target.getParent();
    PsiElement scope = PsiTreeUtil.getParentOfType(declaration, HaxeMethod.class, HaxeFunctionLiteral.class, HaxeClass.class);
    return scope != null ? scope : declaration.getContainingFile();
  }

  /** Mirrors resolveToComponentName(): compare on the component name. */
  private static boolean resolvesTo(PsiElement resolved, HaxeComponentName target) {
    if (resolved == target) return true;
    if (resolved instanceof HaxeNamedComponent namedComponent) {
      return namedComponent.getComponentName() == target;
    }
    return false;
  }
}
