package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/** AST predicates, tree walks and settings queries shared by the formatter's processors. */
public final class HaxeFormatterNodes {

  private HaxeFormatterNodes() {
  }

  /** A chained call link: a REFERENCE_EXPRESSION whose receiver (first child) is itself a call. */
  public static boolean isChainLink(@Nullable ASTNode reference) {
    if (reference == null || reference.getElementType() != REFERENCE_EXPRESSION) return false;
    ASTNode receiver = reference.getFirstChildNode();
    return receiver != null && receiver.getElementType() == CALL_EXPRESSION;
  }

  /** The node or its outermost ancestor reached by climbing while the parent is one of {@code kinds}. */
  @NotNull
  public static ASTNode outermostOfKind(@NotNull ASTNode node, @NotNull TokenSet kinds) {
    ASTNode root = node;
    while (root.getTreeParent() != null && kinds.contains(root.getTreeParent().getElementType())) {
      root = root.getTreeParent();
    }
    return root;
  }

  /** A region's #if or #end reached from a leaf (null when unterminated that way) and whether a newline was crossed on the way. */
  public record RegionEnd(@Nullable ASTNode directive, boolean crossedNewline) {
  }

  /** The #if opening the region the leaf belongs to, through nested regions, and whether a newline lies on the way. */
  @NotNull
  public static RegionEnd regionOpener(@NotNull ASTNode leaf) {
    return regionEnd(leaf, false);
  }

  /** The #end closing the region the leaf belongs to, through nested regions, and whether a newline lies on the way. */
  @NotNull
  public static RegionEnd regionCloser(@NotNull ASTNode leaf) {
    return regionEnd(leaf, true);
  }

  private static RegionEnd regionEnd(ASTNode leaf, boolean forward) {
    IElementType target = forward ? PPEND : PPIF;
    IElementType nested = forward ? PPIF : PPEND;
    int depth = 0;
    boolean crossedNewline = false;
    PsiElement current = leaf.getPsi();
    while ((current = neighbourLeaf(current, forward)) != null) {
      IElementType type = current.getNode().getElementType();
      if (type == target && depth == 0) return new RegionEnd(current.getNode(), crossedNewline);
      if (type == target) depth--;
      else if (type == nested) depth++;
      else if (current.textContains('\n')) crossedNewline = true;
    }
    return new RegionEnd(null, crossedNewline);
  }

  @Nullable
  private static PsiElement neighbourLeaf(PsiElement leaf, boolean forward) {
    return forward ? PsiTreeUtil.nextLeaf(leaf) : PsiTreeUtil.prevLeaf(leaf);
  }

  /** The brace style puts a '{' on its own line: NEXT_LINE, NEXT_LINE_SHIFTED or NEXT_LINE_SHIFTED2. */
  public static boolean nextLineBraces(@NotNull CommonCodeStyleSettings settings) {
    return settings.BRACE_STYLE == CommonCodeStyleSettings.NEXT_LINE
           || settings.BRACE_STYLE == CommonCodeStyleSettings.NEXT_LINE_SHIFTED
           || settings.BRACE_STYLE == CommonCodeStyleSettings.NEXT_LINE_SHIFTED2;
  }
}
