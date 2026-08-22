package com.intellij.plugins.haxe.ide.injection;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.plugins.haxe.ide.annotator.semantics.AnnotatorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * No completion runs in analysis-exempt code: doc-comment fences are
 * highlight-only (item insertion through the fragment's DocumentWindow is
 * not supported), and inactive conditional branches behaved as comments
 * before they were lazily parsed - typing there stays quiet.
 */
public class HaxeExemptCodeCompletionBlocker extends CompletionContributor {

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    if (AnnotatorUtil.isInAnalysisExemptCode(parameters.getPosition())) {
      result.stopHere();
    }
  }
}
