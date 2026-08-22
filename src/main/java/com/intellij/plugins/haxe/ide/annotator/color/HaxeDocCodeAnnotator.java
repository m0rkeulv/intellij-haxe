package com.intellij.plugins.haxe.ide.annotator.color;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.ide.highlight.HaxeSyntaxHighlighterColors;
import com.intellij.plugins.haxe.lang.parser.HaxeDocMarkdown;
import com.intellij.plugins.haxe.lang.parser.HaxePsiDocCommentImpl;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline markdown code spans in doc comments get the doc-code attribute.
 * Fenced code blocks are handled by language injection instead (see
 * HaxeDocFenceInjector), so this only touches prose lines.
 */
public class HaxeDocCodeAnnotator implements Annotator, DumbAware {

  // an inline code span on one line: `content` without inner backticks
  private static final Pattern INLINE_SPAN = Pattern.compile("`[^`\r\n]+`");

  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (!(element instanceof HaxePsiDocCommentImpl docComment)) return;

    for (HaxeDocMarkdown.DocLine line : HaxeDocMarkdown.scan(docComment).proseLines()) {
      Matcher matcher = INLINE_SPAN.matcher(line.text());
      while (matcher.find()) {
        TextRange range = TextRange.create(line.startOffset() + matcher.start(), line.startOffset() + matcher.end());
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
          .range(range)
          .textAttributes(HaxeSyntaxHighlighterColors.DOC_CODE)
          .create();
      }
    }
  }
}
