package com.intellij.plugins.haxe.model.evaluator.callexpression;

import com.intellij.plugins.haxe.model.type.SpecificTypeReference;
import com.intellij.psi.PsiElement;
import lombok.Getter;

@Getter
public class CallExpressionArgumentModel {
  PsiElement psiElement;
  SpecificTypeReference type;
  boolean canCache;
  /**
   * Alignment placeholder for evaluate-with-hole queries: the argument whose
   * own type is the unknown being asked for. It consumes its parameter slot
   * during mapping but is never evaluated, never type-checked and never
   * binds type parameters.
   */
  boolean hole;
  /**
   * The argument's own evaluation observed truncated data (a recursion
   * prevention fired beneath it), so its recorded type may be unreliable.
   * Marks the snapshot entry as a candidate for re-evaluation once the
   * call's type parameters are bound; NOT a reason to suppress diagnostics -
   * most preventions are benign (typedef unwrap cycles, repeat resolution)
   * and suppression hides real errors.
   */
  boolean incomplete;

  public CallExpressionArgumentModel(PsiElement psiElement, SpecificTypeReference type, boolean canCache) {
    this(psiElement, type, canCache, false);
  }

  private CallExpressionArgumentModel(PsiElement psiElement, SpecificTypeReference type, boolean canCache, boolean incomplete) {
    this.psiElement = psiElement;
    this.type = type;
    this.canCache = canCache;
    this.hole = false;
    this.incomplete = incomplete;
  }

  private CallExpressionArgumentModel(PsiElement psiElement) {
    this.psiElement = psiElement;
    this.type = SpecificTypeReference.getUnknown(psiElement);
    // cacheable: hole evaluations are cached under a key that INCLUDES the
    // hole index, so a stored one is only ever reused for the same query
    this.canCache = true;
    this.hole = true;
    this.incomplete = false;
  }

  public static CallExpressionArgumentModel create(PsiElement psiElement, SpecificTypeReference type, boolean canCache) {
    return new CallExpressionArgumentModel(psiElement, type, canCache);
  }

  public static CallExpressionArgumentModel create(PsiElement psiElement, SpecificTypeReference type, boolean canCache, boolean incomplete) {
    return new CallExpressionArgumentModel(psiElement, type, canCache, incomplete);
  }

  public static CallExpressionArgumentModel holeArgument(PsiElement psiElement) {
    return new CallExpressionArgumentModel(psiElement);
  }
}
