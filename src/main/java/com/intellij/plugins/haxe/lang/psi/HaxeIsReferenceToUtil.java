package com.intellij.plugins.haxe.lang.psi;

import com.intellij.plugins.haxe.lang.psi.impl.HaxeTypeParameterDeclaration;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.intellij.plugins.haxe.lang.psi.HaxeResolveChecks.*;

/**
 * A util that allows us to perform faster isReferenceTo checks for local fields.
 * Normal resolve can be very slow for untyped parameters as the default Resolve
 * will attempt to find type in some of the resolve steps.
 *
 * Since this is just a check if the reference is to a local component,
 * we don't need to try to find EnumValues or class types.  we can
 * save time by just doing the local steps.
 */
public final class HaxeIsReferenceToUtil {


  /** Candidate kinds only the tree-walk check family can resolve to. */
  public static boolean isLocalScopedTarget(@NotNull HaxeComponentName componentName) {
    PsiElement declaration = componentName.getParent();
    return declaration instanceof HaxeParameter
           || declaration instanceof HaxeLocalVarDeclaration
           || declaration instanceof HaxeLocalFunctionDeclaration
           || declaration instanceof HaxeSwitchCaseCaptureVar
           || declaration instanceof HaxeEnumExtractedValueReference
           || declaration instanceof HaxeTypeParameterDeclaration;
  }

  /**
   * @return true/false when the fast path can answer; null when the
   * occurrence needs the full pipeline (switch-case pattern position).
   */
  public static @Nullable Boolean tryIsReferenceTo(@NotNull HaxeReference reference, @NotNull HaxeComponentName target) {
    // ignore enum constructors
    if (inSwitchCasePatternPosition(reference)) return null;

    // locals are never accessed in chain (expr.name)
    if (isMemberPartOfQualifiedChain(reference)) return Boolean.FALSE;

    // a local is only visible inside the scope owning its declaration
    PsiElement scope = declaringScope(target);
    if (!PsiTreeUtil.isAncestor(scope, reference, false)) return Boolean.FALSE;

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
    return resolvesTo(result.getFirst(), target);
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
   * Nearest enclosing function for parameters and locals, class for class typeParameters.
   */
  private static @NotNull PsiElement declaringScope(HaxeComponentName target) {
    PsiElement declaration = target.getParent();
    PsiElement scope = PsiTreeUtil.getParentOfType(declaration, HaxeMethod.class, HaxeFunctionLiteral.class, HaxeClass.class);
    return scope != null ? scope : declaration.getContainingFile();
  }

  private static boolean resolvesTo(PsiElement resolved, HaxeComponentName target) {
    if (resolved == target) return true;
    if (resolved instanceof HaxeNamedComponent namedComponent) {
      return namedComponent.getComponentName() == target;
    }
    return false;
  }
}
