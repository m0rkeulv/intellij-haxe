package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.wrapping.HaxeOperatorChainRules.Kind;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.LOCAL_VAR_DECLARATION;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.LOCAL_VAR_DECLARATION_LIST;

/**
 * The joined line of a node once the rules' earlier wraps are applied: a
 * multi-var split puts the node's declarator on its own line, an exploding
 * operator chain its operand (one step in from the chain's own line), and
 * a chopped method chain then cuts the line down to the node's link.
 * <p>
 * The resolution recurses - an exploded operand's line needs its chain's,
 * and a chain's split is judged on the line of the list or value holding
 * it - but every nested call is on a STRICT ancestor of the node it came
 * from, so the recursion is bounded by the tree depth.
 */
final class HaxeWrapLines {

  private HaxeWrapLines() {
  }

  @Nullable
  static HaxeJoinedLine lineOf(@NotNull ASTNode node, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine.Context context = HaxeJoinedLine.Context.of(node, common);
    if (context == null) return null;
    HaxeJoinedLine line = context.statementLine();

    ASTNode declarator = splitDeclaratorOf(node, context.statement(), common, haxe);
    if (declarator != null) {
      line = context.declaratorLine(declarator);
    }

    ASTNode operand = explodedChainOperandOf(node, context.statement(), common, haxe);
    if (operand != null) {
      HaxeJoinedLine chainLine = lineOf(operand.getTreeParent(), common, haxe);
      int chainIndent = chainLine == null ? context.indent() : chainLine.indent();
      line = context.operandLine(operand, chainIndent);
    }

    return context.choppedLinkLine(line, node);
  }

  /** The declarator holding the node, when its declaration list splits one per line. */
  @Nullable
  private static ASTNode splitDeclaratorOf(ASTNode node, ASTNode statement, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    for (ASTNode ancestor = node; ancestor != null && ancestor != statement; ancestor = ancestor.getTreeParent()) {
      if (ancestor.getElementType() != LOCAL_VAR_DECLARATION) continue;
      ASTNode list = ancestor.getTreeParent();
      boolean splits = list != null
                       && list.getElementType() == LOCAL_VAR_DECLARATION_LIST
                       && HaxeMultiVarSplit.splits(list, common, haxe);
      return splits ? ancestor : null;
    }
    return null;
  }

  /**
   * The innermost operand holding the node whose operator chain (of either
   * kind) explodes, below the statement; null when no enclosing chain does.
   */
  @Nullable
  private static ASTNode explodedChainOperandOf(ASTNode node, ASTNode statement, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    for (ASTNode ancestor = node; ancestor != null && ancestor != statement; ancestor = ancestor.getTreeParent()) {
      ASTNode parent = ancestor.getTreeParent();
      if (parent == null) return null;
      for (Kind kind : Kind.values()) {
        boolean operand = kind.chainTypes.contains(parent.getElementType()) && !kind.isOperator(ancestor)
                          && !kind.chainTypes.contains(ancestor.getElementType());
        if (operand && HaxeOperatorChainRules.explodes(kind, parent, common, haxe)) return ancestor;
      }
    }
    return null;
  }
}
