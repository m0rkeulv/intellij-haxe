package com.intellij.plugins.haxe.ide.injection;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.plugins.haxe.ide.annotator.semantics.AnnotatorUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * No inspection runs inside a doc comment's injected code fences: sample
 * snippets resolve nothing and declare unused names by design, so unresolved
 * and unused markers there would be pure noise. Covers the inspections that
 * bypass the HaxeInspection base (and its shouldSkip guard) with their own
 * visitors.
 */
public class HaxeDocFragmentInspectionSuppressor implements InspectionSuppressor {

  @Override
  public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
    return AnnotatorUtil.isInDocCodeFragment(element);
  }

  @Override
  public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
    return SuppressQuickFix.EMPTY_ARRAY;
  }
}
