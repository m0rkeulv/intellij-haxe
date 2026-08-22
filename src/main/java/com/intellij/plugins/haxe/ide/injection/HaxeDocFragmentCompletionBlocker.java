package com.intellij.plugins.haxe.ide.injection;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.plugins.haxe.ide.annotator.semantics.AnnotatorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Doc-comment code fences are injected for highlighting only: no completion
 * runs inside them. Sample snippets resolve nothing, and item insertion
 * through the fragment's DocumentWindow is not supported.
 */
public class HaxeDocFragmentCompletionBlocker extends CompletionContributor {

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    if (AnnotatorUtil.isInDocCodeFragment(parameters.getPosition())) {
      result.stopHere();
    }
  }
}
