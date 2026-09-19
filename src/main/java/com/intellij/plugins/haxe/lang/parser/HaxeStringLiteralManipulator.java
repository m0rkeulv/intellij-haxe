package com.intellij.plugins.haxe.lang.parser;

import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.lang.psi.HaxeStringLiteralExpression;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeStringLiteralImpl;
import com.intellij.psi.AbstractElementManipulator;
import org.jetbrains.annotations.NotNull;

public class HaxeStringLiteralManipulator extends AbstractElementManipulator<HaxeStringLiteralImpl> {
  @Override
  public @NotNull TextRange getRangeInElement(@NotNull HaxeStringLiteralImpl element) {
    String text = element.getText();
    boolean closed = text.length() > 1 && text.charAt(text.length() - 1) == text.charAt(0);
    return new TextRange(1, closed ? text.length() - 1 : text.length());
  }

  @Override
  public HaxeStringLiteralImpl handleContentChange(@NotNull HaxeStringLiteralImpl element,
                                                         @NotNull TextRange range, String newContent) {
    //String oldText = element.getText();
    //String escaped = escapeFor(oldText.charAt(0), newContent); // \ and the quote char; also $ inside '...'
    //String newText = oldText.substring(0, range.getStartOffset()) + escaped + oldText.substring(range.getEndOffset());
    return (HaxeStringLiteralImpl) element.updateText(newContent);
  }
}