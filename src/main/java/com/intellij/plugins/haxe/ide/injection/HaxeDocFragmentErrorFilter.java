package com.intellij.plugins.haxe.ide.injection;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.plugins.haxe.ide.annotator.semantics.AnnotatorUtil;
import com.intellij.psi.PsiErrorElement;
import org.jetbrains.annotations.NotNull;

/**
 * Doc-comment code fences are injected for highlighting only; incomplete
 * sample snippets are normal there, so parse errors stay invisible.
 */
public class HaxeDocFragmentErrorFilter extends HighlightErrorFilter {

  @Override
  public boolean shouldHighlightErrorElement(@NotNull PsiErrorElement element) {
    return !AnnotatorUtil.isInDocCodeFragment(element);
  }
}
