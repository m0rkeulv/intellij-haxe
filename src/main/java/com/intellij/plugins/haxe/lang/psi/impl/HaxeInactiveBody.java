package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.plugins.haxe.lang.lexer.HaxeInactiveBodyElementType;
import com.intellij.psi.PsiComment;
import com.intellij.psi.impl.source.tree.LazyParseablePsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An inactive conditional-compilation branch: still a PsiComment to every
 * existing consumer (dead code stays comment-like), but lazily parseable into
 * a real sub-tree for formatting and highlighting. Whether the content parsed
 * or fell back to raw token soup shows in the children's element types.
 */
public class HaxeInactiveBody extends LazyParseablePsiElement implements PsiComment {

  public HaxeInactiveBody(@NotNull IElementType type, @Nullable CharSequence text) {
    super(type, text);
  }

  @Override
  public @NotNull IElementType getTokenType() {
    return getElementType();
  }

  // the label PsiCommentImpl used - keeps parse-tree dumps (and their test goldens) stable
  @Override
  public String toString() {
    return "PsiComment(" + getElementType() + ")";
  }

  /**
   * Whether grading produced real structure or fell back to raw token soup.
   * Formatting reaches inside structured branches; soup is preserved
   * verbatim, like haxe-formatter does. Touching the first child forces the
   * lazy parse that records the grade.
   */
  public boolean hasParsedStructure() {
    return getNode().getFirstChildNode() != null
           && getNode().getUserData(HaxeInactiveBodyElementType.PARSED_GRADE) != null;
  }
}
