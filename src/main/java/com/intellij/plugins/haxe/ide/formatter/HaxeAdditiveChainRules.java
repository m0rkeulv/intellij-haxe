package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.ARGUMENT_LISTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's wrapping.opAddSubChain rules for a +/- chain, decided the
 * way the tool decides them: every chain sitting directly in one scope (the
 * arguments of a call, the items of a literal, a parenthesized expression,
 * a value) is judged together, its items being all their operands, against
 * the line the scope's opener sits on once the tool's own argument fill has
 * broken it. Rules, first match wins:
 *
 * <pre>
 * line past LINE_LENGTH and an operand of ITEM_LENGTH  -> EXPLODE (a break before every operator)
 * line past LINE_LENGTH                                -> FILL    (a break before an operator that overflows)
 * up to 3 operands and the line fits the margin        -> NONE
 * total up to TOTAL_LENGTH and the line fits           -> NONE
 * ITEM_COUNT operands or more                          -> EXPLODE
 * otherwise                                            -> NONE
 * </pre>
 *
 * Lengths come from the joined line ({@link HaxeJoinedLine}); in the total
 * every operand counts two more, the separator that follows it.
 */
final class HaxeAdditiveChainRules {

  private enum Split { NONE, FILL, EXPLODE }

  // a chain's scope: the innermost list, parens or value holding it, else its statement's container
  private static final TokenSet SCOPES = TokenSet.orSet(
    ARGUMENT_LISTS,
    HaxeJoinedLine.STATEMENT_CONTAINERS,
    TokenSet.create(NEW_EXPRESSION, PARENTHESIZED_EXPRESSION, ARRAY_LITERAL, MAP_INITIALIZER_EXPRESSION_LIST, OBJECT_LITERAL_ELEMENT,
                    VAR_INIT, ASSIGN_EXPRESSION, RETURN_STATEMENT));

  private HaxeAdditiveChainRules() {
  }

  /**
   * Whether the chain breaks before this operator: always when it explodes;
   * when it fills, where the operand after the operator, with the operator
   * and space that trail it, would reach the margin on the joined line (the
   * fill then continues one step in, operator leading).
   */
  static boolean breaksBefore(@NotNull ASTNode operator, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    Split split = decide(operator, common, haxe);
    if (split == Split.NONE) return false;
    if (split == Split.EXPLODE) return true;
    HaxeJoinedLine line = HaxeJoinedLine.of(operator, common);
    if (line == null) return false;
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    int tabSize = common.getIndentOptions() == null ? 4 : common.getIndentOptions().TAB_SIZE;
    List<ASTNode> operands = new ArrayList<>();
    collectOperands(chainRoot(operator), operands);
    ASTNode following = nextCode(operator);
    int shift = 0;
    for (int i = 1; i < operands.size(); i++) {
      ASTNode operand = operands.get(i);
      int trailing = i < operands.size() - 1 ? 3 : 0;
      boolean breaks = line.columnAfter(operand) + shift + trailing >= margin;
      if (breaks) {
        // the operand now starts after the leading operator on its own line
        shift = line.indent() + tabSize + 2 - line.columnBefore(operand);
      }
      if (operand == following) return breaks;
    }
    return false;
  }

  @Nullable
  private static ASTNode nextCode(ASTNode node) {
    ASTNode next = node.getTreeNext();
    while (next != null && (WHITESPACES.contains(next.getElementType()) || COMMENTS.contains(next.getElementType()))) {
      next = next.getTreeNext();
    }
    return next;
  }

  /** The split for the chain holding this additive operator node. */
  private static Split decide(@NotNull ASTNode operator, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    if (haxe.ADD_CHAIN_SPLIT_LINE_LENGTH <= 0) return Split.NONE;
    ASTNode chain = chainRoot(operator);
    ASTNode scope = scopeOf(chain);
    List<ASTNode> operands = new ArrayList<>();
    for (ASTNode item : scopeItems(scope, chain)) {
      collectOperands(item, operands);
    }
    int total = 0;
    int longest = 0;
    for (ASTNode operand : operands) {
      int width = oneLineWidth(operand);
      // an operand counts the separator that follows it
      total += width + 2;
      longest = Math.max(longest, width);
    }

    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    int lineLength = judgedLineLength(scope, common);
    boolean exceeds = lineLength > margin;
    if (lineLength > haxe.ADD_CHAIN_SPLIT_LINE_LENGTH && longest >= haxe.ADD_CHAIN_SPLIT_ITEM_LENGTH) return Split.EXPLODE;
    if (lineLength > haxe.ADD_CHAIN_SPLIT_LINE_LENGTH) return Split.FILL;
    if (operands.size() <= 3 && !exceeds) return Split.NONE;
    if (total <= haxe.ADD_CHAIN_SPLIT_TOTAL_LENGTH && !exceeds) return Split.NONE;
    if (operands.size() >= haxe.ADD_CHAIN_SPLIT_ITEM_COUNT) return Split.EXPLODE;
    return Split.NONE;
  }

  /** The outermost additive level the operator's chain belongs to. */
  private static ASTNode chainRoot(ASTNode operator) {
    ASTNode root = operator.getTreeParent();
    while (root.getTreeParent() != null && root.getTreeParent().getElementType() == ADDITIVE_EXPRESSION) {
      root = root.getTreeParent();
    }
    return root;
  }

  private static ASTNode scopeOf(ASTNode chain) {
    ASTNode scope = chain.getTreeParent();
    while (scope != null && !SCOPES.contains(scope.getElementType())) {
      scope = scope.getTreeParent();
    }
    return scope == null ? chain : scope;
  }

  /** The chains judged together: every additive chain sitting directly in a list scope, else just this one. */
  private static List<ASTNode> scopeItems(ASTNode scope, ASTNode chain) {
    boolean list = ARGUMENT_LISTS.contains(scope.getElementType())
                   || scope.getElementType() == NEW_EXPRESSION
                   || scope.getElementType() == MAP_INITIALIZER_EXPRESSION_LIST;
    if (!list) return List.of(chain);
    List<ASTNode> items = new ArrayList<>();
    for (ASTNode child = scope.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child.getElementType() == ADDITIVE_EXPRESSION) items.add(child);
    }
    return items;
  }

  private static void collectOperands(ASTNode chain, List<ASTNode> operands) {
    for (ASTNode child = chain.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (WHITESPACES.contains(type) || COMMENTS.contains(type) || isAdditiveOperator(child)) continue;
      if (type == ADDITIVE_EXPRESSION) {
        collectOperands(child, operands);
      }
      else {
        operands.add(child);
      }
    }
  }

  static boolean isAdditiveOperator(ASTNode node) {
    ASTNode first = node.getFirstChildNode();
    return ADDITIVE_OPERATORS.contains(first == null ? node.getElementType() : first.getElementType());
  }

  /**
   * The joined line of the scope, cut where the tool's argument fill breaks
   * it first: at the comma before the first argument the fill moves down.
   */
  private static int judgedLineLength(ASTNode scope, CommonCodeStyleSettings common) {
    HaxeJoinedLine line = HaxeJoinedLine.of(scope, common);
    if (line == null) return 0;
    boolean callArguments = ARGUMENT_LISTS.contains(scope.getElementType()) || scope.getElementType() == NEW_EXPRESSION;
    if (!callArguments) return line.width();
    List<ASTNode> broken = HaxeCallFill.brokenArguments(scope, common);
    if (broken.isEmpty()) return line.width();
    return line.columnBefore(broken.getFirst()) - 1;
  }

  /** The node's width as it prints on one line: every whitespace run one space. */
  static int oneLineWidth(ASTNode node) {
    CharSequence chars = node.getChars();
    int width = 0;
    boolean inWhitespace = false;
    for (int i = 0; i < chars.length(); i++) {
      boolean whitespace = Character.isWhitespace(chars.charAt(i));
      if (!whitespace || !inWhitespace) width++;
      inWhitespace = whitespace;
    }
    return width;
  }
}
