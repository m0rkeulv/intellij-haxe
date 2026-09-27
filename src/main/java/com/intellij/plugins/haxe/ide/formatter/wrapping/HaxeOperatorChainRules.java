package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.ARGUMENT_LISTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's operator chain rules - wrapping.opAddSubChain for +/-
 * chains, wrapping.opBoolChain for &&/|| chains - decided the way the tool
 * decides them: every chain of the kind sitting directly in one holder (the
 * arguments of a call, the items of a literal, a parenthesized expression,
 * a value) is judged together, its items being all their operands, against
 * the line the holder's opener sits on once the tool's own argument fill has
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
 * A threshold of 0 takes its rule out of the list; all four at 0 leave the
 * chain as written. Lengths come from the joined line ({@link HaxeJoinedLine}); in the total
 * every operand counts two more, the separator that follows it. A chain's
 * decision is memoized on its root ({@link HaxeWrapMemo}).
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
    private final Key<HaxeWrapMemo.Entry<Decision>> decisionMemo = Key.create("HaxeOperatorChainRules." + name());

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
      return switch (this) {
        case ADDITIVE -> new Thresholds(
          haxe.ADD_CHAIN_SPLIT_LINE_LENGTH,
          haxe.ADD_CHAIN_SPLIT_ITEM_LENGTH,
          haxe.ADD_CHAIN_SPLIT_ITEM_COUNT,
          haxe.ADD_CHAIN_SPLIT_TOTAL_LENGTH);
        case LOGIC -> new Thresholds(
          haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH,
          haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH,
          haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT,
          haxe.BOOL_CHAIN_SPLIT_TOTAL_LENGTH);
      };
    }
  }

  /** The kind's thresholds as configured; a threshold of 0 takes its rule out. */
  record Thresholds(int lineLength, int itemLength, int itemCount, int totalLength) {
    boolean allOff() {
      return lineLength <= 0 && itemLength <= 0 && itemCount <= 0 && totalLength <= 0;
    }
  }

  private enum Split { NONE, FILL, EXPLODE }

  /** A chain's split and, when it fills, the operands the fill moves to a new line. */
  private record Decision(Split split, Set<ASTNode> fillBreaks) {
  }

  // a chain's holder: the innermost list, parens or value holding it, else its statement's container
  private static final TokenSet HOLDERS = TokenSet.orSet(
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
    Decision decision = decide(kind, chainRootOf(kind, operator), common, haxe);
    if (decision.split() == Split.NONE) return false;
    if (decision.split() == Split.EXPLODE) return true;
    ASTNode following = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(operator);
    return following != null && decision.fillBreaks().contains(following);
  }

  /** Whether the chain this level belongs to explodes - every operand then starts its own line. */
  static boolean explodes(@NotNull Kind kind, @NotNull ASTNode chainLevel,
                          @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    ASTNode root = HaxeFormatterNodes.outermostOfKind(chainLevel, kind.chainTypes);
    return decide(kind, root, common, haxe).split() == Split.EXPLODE;
  }

  /** The outermost level of the kind the operator's chain belongs to. */
  private static ASTNode chainRootOf(Kind kind, ASTNode operator) {
    return HaxeFormatterNodes.outermostOfKind(operator.getTreeParent(), kind.chainTypes);
  }

  private static Decision decide(Kind kind, ASTNode chain, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    return HaxeWrapMemo.cached(chain, kind.decisionMemo, common, haxe, () -> decideRoot(kind, chain, common, haxe));
  }

  private static Decision decideRoot(Kind kind, ASTNode chain, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    Split split = splitOf(kind, chain, common, haxe);
    Set<ASTNode> fillBreaks = split == Split.FILL ? fillBreaksOf(kind, chain, common, haxe) : Set.of();
    return new Decision(split, fillBreaks);
  }

  private static Split splitOf(Kind kind, ASTNode chain, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    Thresholds thresholds = kind.thresholds(haxe);
    if (thresholds.allOff()) return Split.NONE;
    ASTNode holder = holderOf(chain);
    List<ASTNode> operands = new ArrayList<>();
    for (ASTNode item : holderItems(kind, holder, chain)) {
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
    ASTNode measured = HaxeJoinedLine.STATEMENT_CONTAINERS.contains(holder.getElementType()) ? chain : holder;
    int lineLength = measuredLineLength(measured, common, haxe);
    boolean exceeds = lineLength > margin;
    // a threshold of 0 takes its rule out; the others still apply
    boolean longLine = thresholds.lineLength() > 0 && lineLength >= thresholds.lineLength();
    boolean longOperand = thresholds.itemLength() > 0 && longest >= thresholds.itemLength();
    boolean smallTotal = thresholds.totalLength() > 0 && total <= thresholds.totalLength();
    boolean manyOperands = thresholds.itemCount() > 0 && operands.size() >= thresholds.itemCount();
    if (longLine && longOperand) return Split.EXPLODE;
    if (longLine) return Split.FILL;
    if (operands.size() <= KEEP_ITEM_COUNT && !exceeds) return Split.NONE;
    if (smallTotal && !exceeds) return Split.NONE;
    if (manyOperands) return Split.EXPLODE;
    return Split.NONE;
  }

  /**
   * The operands a filling chain moves to a new line: each that, with the
   * operator and space trailing it, would reach the margin on the chain's
   * joined line; the fill then continues one step in, operator leading.
   */
  private static Set<ASTNode> fillBreaksOf(Kind kind, ASTNode chain, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeWrapLines.lineOf(chain, common, haxe);
    if (line == null) return Set.of();
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    int tabSize = HaxeJoinedLine.tabSize(common);
    List<ASTNode> operands = new ArrayList<>();
    collectOperands(kind, chain, operands);
    Set<ASTNode> breaks = new HashSet<>();
    int shift = 0;
    for (int i = 1; i < operands.size(); i++) {
      ASTNode operand = operands.get(i);
      int trailing = i < operands.size() - 1 ? kind.operatorWidth + HaxeJoinedLine.SEPARATOR_WIDTH : 0;
      if (line.columnAfter(operand) + shift + trailing < margin) continue;
      breaks.add(operand);
      // the operand now starts after the leading operator on its own line
      shift = line.indent() + tabSize + kind.operatorWidth + 1 - line.columnBefore(operand);
    }
    return breaks;
  }

  private static ASTNode holderOf(ASTNode chain) {
    ASTNode holder = chain.getTreeParent();
    while (holder != null && !HOLDERS.contains(holder.getElementType())) {
      holder = holder.getTreeParent();
    }
    return holder == null ? chain : holder;
  }

  /** The chains judged together: every chain of the kind sitting directly in a list holder, else just this one. */
  private static List<ASTNode> holderItems(Kind kind, ASTNode holder, ASTNode chain) {
    boolean list = ARGUMENT_LISTS.contains(holder.getElementType())
                   || holder.getElementType() == NEW_EXPRESSION
                   || holder.getElementType() == MAP_INITIALIZER_EXPRESSION_LIST;
    if (!list) return List.of(chain);
    List<ASTNode> items = new ArrayList<>();
    for (ASTNode child = holder.getFirstChildNode(); child != null; child = child.getTreeNext()) {
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
   * The joined line of the holder (or the chain judged on its own line), cut
   * where the tool's argument fill breaks it first: at the comma before the
   * first argument the fill moves down.
   */
  private static int measuredLineLength(ASTNode measured, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeWrapLines.lineOf(measured, common, haxe);
    if (line == null) return 0;
    boolean callArguments = ARGUMENT_LISTS.contains(measured.getElementType()) || measured.getElementType() == NEW_EXPRESSION;
    if (!callArguments) return line.width();
    List<ASTNode> broken = HaxeCallArgumentFill.brokenArguments(measured, common, haxe);
    if (broken.isEmpty()) return line.width();
    return line.columnBefore(broken.getFirst()) - 1;
  }
}
