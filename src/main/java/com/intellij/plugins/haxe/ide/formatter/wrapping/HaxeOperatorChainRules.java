package com.intellij.plugins.haxe.ide.formatter.wrapping;

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
 * haxe-formatter's operator chain rules - wrapping.opAddSubChain for +/-
 * chains, wrapping.opBoolChain for &&/|| chains - decided the way the tool
 * decides them: every chain of the kind sitting directly in one scope (the
 * arguments of a call, the items of a literal, a parenthesized expression,
 * a value) is judged together, its items being all their operands, against
 * the line the scope's opener sits on once the tool's own argument fill has
 * broken it. Rules, first match wins (the kind's thresholds):
 *
 * <pre>
 * line reaching LINE_LENGTH and an operand reaching ITEM_LENGTH -> EXPLODE (a break before every operator)
 * line reaching LINE_LENGTH                                     -> FILL    (a break before an operator that overflows)
 * up to KEEP_ITEM_COUNT operands and the line fits the margin   -> NONE
 * total up to TOTAL_LENGTH and the line fits                    -> NONE
 * ITEM_COUNT operands or more                                   -> EXPLODE
 * otherwise                                                     -> NONE
 * </pre>
 *
 * Lengths come from the joined line ({@link HaxeJoinedLine}); in the total
 * every operand counts two more, the separator that follows it.
 */
public final class HaxeOperatorChainRules {

  // the noWrap rule both kinds share: a chain of this many operands or fewer
  // on a line within the margin is left alone (not configurable in the tool)
  public static final int KEEP_ITEM_COUNT = 3;

  public enum Kind {
    ADDITIVE(TokenSet.create(ADDITIVE_EXPRESSION), ADDITIVE_OPERATORS, 1),
    LOGIC(TokenSet.create(LOGIC_AND_EXPRESSION, LOGIC_OR_EXPRESSION), LOGIC_OPERATORS, 2);

    final TokenSet chainTypes;
    final TokenSet operators;
    // the operator's own width: "+" one column, "&&" two
    final int operatorWidth;

    Kind(TokenSet chainTypes, TokenSet operators, int operatorWidth) {
      this.chainTypes = chainTypes;
      this.operators = operators;
      this.operatorWidth = operatorWidth;
    }

    /** The node is one of the kind's operator elements (the sign wrapped in its operator element, or bare). */
    boolean isOperator(ASTNode node) {
      ASTNode first = node.getFirstChildNode();
      return operators.contains(first == null ? node.getElementType() : first.getElementType());
    }

    Thresholds thresholds(HaxeCodeStyleSettings haxe) {
      return this == ADDITIVE
             ? new Thresholds(haxe.ADD_CHAIN_SPLIT_LINE_LENGTH, haxe.ADD_CHAIN_SPLIT_ITEM_LENGTH, haxe.ADD_CHAIN_SPLIT_ITEM_COUNT, haxe.ADD_CHAIN_SPLIT_TOTAL_LENGTH)
             : new Thresholds(haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH, haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH, haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT, haxe.BOOL_CHAIN_SPLIT_TOTAL_LENGTH);
    }
  }

  /** The kind's thresholds as configured; a line length of 0 turns the rules off. */
  private record Thresholds(int lineLength, int itemLength, int itemCount, int totalLength) {
  }

  private enum Split { NONE, FILL, EXPLODE }

  // a chain's scope: the innermost list, parens or value holding it, else its statement's container
  private static final TokenSet SCOPES = TokenSet.orSet(
    ARGUMENT_LISTS,
    HaxeJoinedLine.STATEMENT_CONTAINERS,
    TokenSet.create(NEW_EXPRESSION, PARENTHESIZED_EXPRESSION, ARRAY_LITERAL, MAP_INITIALIZER_EXPRESSION_LIST, OBJECT_LITERAL_ELEMENT,
                    VAR_INIT, ASSIGN_EXPRESSION, RETURN_STATEMENT));

  private HaxeOperatorChainRules() {
  }

  /**
   * Whether the chain breaks before this operator: always when it explodes;
   * when it fills, where the operand after the operator, with the operator
   * and space that trail it, would reach the margin on the joined line (the
   * fill then continues one step in, operator leading).
   */
  public static boolean breaksBefore(@NotNull Kind kind, @NotNull ASTNode operator,
                                     @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    Split split = decide(kind, operator, common, haxe);
    if (split == Split.NONE) return false;
    if (split == Split.EXPLODE) return true;
    HaxeJoinedLine line = HaxeJoinedLine.of(operator, common, haxe);
    if (line == null) return false;
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    int tabSize = HaxeJoinedLine.tabSize(common);
    List<ASTNode> operands = new ArrayList<>();
    collectOperands(kind, chainRoot(kind, operator), operands);
    ASTNode following = nextCode(operator);
    int shift = 0;
    for (int i = 1; i < operands.size(); i++) {
      ASTNode operand = operands.get(i);
      int trailing = i < operands.size() - 1 ? kind.operatorWidth + HaxeJoinedLine.SEPARATOR_WIDTH : 0;
      boolean breaks = line.columnAfter(operand) + shift + trailing >= margin;
      if (breaks) {
        // the operand now starts after the leading operator on its own line
        shift = line.indent() + tabSize + kind.operatorWidth + 1 - line.columnBefore(operand);
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

  /** Whether the chain this level belongs to explodes - every operand then starts its own line. */
  static boolean explodes(@NotNull Kind kind, @NotNull ASTNode chainLevel,
                          @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    ASTNode root = chainLevel;
    while (root.getTreeParent() != null && kind.chainTypes.contains(root.getTreeParent().getElementType())) {
      root = root.getTreeParent();
    }
    return decideRoot(kind, root, common, haxe) == Split.EXPLODE;
  }

  /** The split for the chain holding this operator node. */
  private static Split decide(Kind kind, ASTNode operator, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    return decideRoot(kind, chainRoot(kind, operator), common, haxe);
  }

  private static Split decideRoot(Kind kind, ASTNode chain, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    Thresholds thresholds = kind.thresholds(haxe);
    if (thresholds.lineLength() <= 0) return Split.NONE;
    ASTNode scope = scopeOf(chain);
    List<ASTNode> operands = new ArrayList<>();
    for (ASTNode item : scopeItems(kind, scope, chain)) {
      collectOperands(kind, item, operands);
    }
    int total = 0;
    int longest = 0;
    for (ASTNode operand : operands) {
      int width = HaxeJoinedLine.oneLineWidth(operand);
      // an operand counts the separator that follows it
      total += width + HaxeJoinedLine.SEPARATOR_WIDTH;
      longest = Math.max(longest, width);
    }

    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    // a chain held by nothing closer than its statement's container is
    // judged on the statement's line - the chain's own
    ASTNode judged = HaxeJoinedLine.STATEMENT_CONTAINERS.contains(scope.getElementType()) ? chain : scope;
    int lineLength = judgedLineLength(judged, common, haxe);
    boolean exceeds = lineLength > margin;
    if (lineLength >= thresholds.lineLength() && longest >= thresholds.itemLength()) return Split.EXPLODE;
    if (lineLength >= thresholds.lineLength()) return Split.FILL;
    if (operands.size() <= KEEP_ITEM_COUNT && !exceeds) return Split.NONE;
    if (total <= thresholds.totalLength() && !exceeds) return Split.NONE;
    if (operands.size() >= thresholds.itemCount()) return Split.EXPLODE;
    return Split.NONE;
  }

  /** The outermost level of the kind the operator's chain belongs to. */
  private static ASTNode chainRoot(Kind kind, ASTNode operator) {
    ASTNode root = operator.getTreeParent();
    while (root.getTreeParent() != null && kind.chainTypes.contains(root.getTreeParent().getElementType())) {
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

  /** The chains judged together: every chain of the kind sitting directly in a list scope, else just this one. */
  private static List<ASTNode> scopeItems(Kind kind, ASTNode scope, ASTNode chain) {
    boolean list = ARGUMENT_LISTS.contains(scope.getElementType())
                   || scope.getElementType() == NEW_EXPRESSION
                   || scope.getElementType() == MAP_INITIALIZER_EXPRESSION_LIST;
    if (!list) return List.of(chain);
    List<ASTNode> items = new ArrayList<>();
    for (ASTNode child = scope.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (kind.chainTypes.contains(child.getElementType())) items.add(child);
    }
    return items;
  }

  private static void collectOperands(Kind kind, ASTNode chain, List<ASTNode> operands) {
    for (ASTNode child = chain.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (WHITESPACES.contains(type) || COMMENTS.contains(type) || kind.isOperator(child)) continue;
      if (kind.chainTypes.contains(type)) {
        collectOperands(kind, child, operands);
      }
      else {
        operands.add(child);
      }
    }
  }

  /**
   * The joined line of the scope, cut where the tool's argument fill breaks
   * it first: at the comma before the first argument the fill moves down.
   */
  private static int judgedLineLength(ASTNode scope, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeJoinedLine.of(scope, common, haxe);
    if (line == null) return 0;
    boolean callArguments = ARGUMENT_LISTS.contains(scope.getElementType()) || scope.getElementType() == NEW_EXPRESSION;
    if (!callArguments) return line.width();
    List<ASTNode> broken = HaxeCallArgumentFill.brokenArguments(scope, common, haxe);
    if (broken.isEmpty()) return line.width();
    return line.columnBefore(broken.getFirst()) - 1;
  }
}
