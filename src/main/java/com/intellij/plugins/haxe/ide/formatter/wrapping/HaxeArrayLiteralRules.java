package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.formatting.WrapType;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.WrappingUtil;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.COMMENTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.WHITESPACES;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's array literal rules (wrapping.arrayWrap), decided the way
 * the tool decides them on the literal's items - each counting the ", "
 * after it, none after the last - and its joined line. First match wins:
 *
 * <pre>
 * an item written over several lines                                        -> ONE_PER_LINE
 * items totalling at most KEEP_TOTAL_LENGTH                                 -> KEEP
 * FILL_EQUAL_ITEM_COUNT items or more of one length, at most FILL_EQUAL_ITEM_LENGTH -> FILL_AFTER_LEADING_BREAK
 * FILL_ITEM_COUNT items or more, each at most FILL_ITEM_LENGTH              -> FILL_AFTER_LEADING_BREAK
 * an item reaching CHOP_ITEM_LENGTH                                         -> ONE_PER_LINE
 * CHOP_ITEM_COUNT items or more                                             -> ONE_PER_LINE
 * the line past the margin under a chopping array wrap setting              -> ONE_PER_LINE
 * otherwise                                                                 -> KEEP
 * </pre>
 *
 * Items are of one length when every width matches, the last item allowed
 * to be its separator short. A threshold of 0 takes its rule out; all seven
 * at 0 leave the literal to the array wrap setting alone ({@link Decision#NONE}).
 * A literal's decision is memoized on it ({@link HaxeWrapMemo}).
 */
public final class HaxeArrayLiteralRules {

  public enum Decision { NONE, KEEP, ONE_PER_LINE, FILL_AFTER_LEADING_BREAK }

  /** The thresholds as configured; a threshold of 0 takes its rule out. */
  record Thresholds(int keepTotalLength, int fillEqualItemLength, int fillEqualItemCount,
                    int fillItemLength, int fillItemCount, int chopItemLength, int chopItemCount) {
    boolean allOff() {
      return keepTotalLength <= 0 && fillEqualItemLength <= 0 && fillEqualItemCount <= 0
             && fillItemLength <= 0 && fillItemCount <= 0 && chopItemLength <= 0 && chopItemCount <= 0;
    }
  }

  private static final Key<HaxeWrapMemo.Entry<Decision>> DECISION_MEMO = Key.create("HaxeArrayLiteralRules.decision");

  private HaxeArrayLiteralRules() {
  }

  static Thresholds thresholds(@NotNull HaxeCodeStyleSettings haxe) {
    return new Thresholds(haxe.ARRAY_KEEP_TOTAL_LENGTH, haxe.ARRAY_FILL_EQUAL_ITEM_LENGTH, haxe.ARRAY_FILL_EQUAL_ITEM_COUNT,
                          haxe.ARRAY_FILL_ITEM_LENGTH, haxe.ARRAY_FILL_ITEM_COUNT, haxe.ARRAY_CHOP_ITEM_LENGTH, haxe.ARRAY_CHOP_ITEM_COUNT);
  }

  /** The array literal's decision; NONE for a comprehension or an empty literal. */
  @NotNull
  public static Decision decide(@NotNull ASTNode literal, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    return HaxeWrapMemo.cached(literal, DECISION_MEMO, common, haxe, () -> decideLiteral(literal, common, haxe));
  }

  private static Decision decideLiteral(ASTNode literal, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    Thresholds thresholds = thresholds(haxe);
    if (thresholds.allOff()) return Decision.NONE;
    List<ASTNode> items = items(literal);
    if (items.isEmpty()) return Decision.NONE;

    int total = 0;
    int longest = 0;
    boolean multilineItem = false;
    boolean equalWidths = true;
    int[] widths = new int[items.size()];
    for (int i = 0; i < items.size(); i++) {
      ASTNode item = items.get(i);
      int separator = i < items.size() - 1 ? HaxeJoinedLine.SEPARATOR_WIDTH : 0;
      widths[i] = HaxeJoinedLine.oneLineWidth(item) + separator;
      total += widths[i];
      longest = Math.max(longest, widths[i]);
      multilineItem |= item.textContains('\n');
      // the last item carries no separator, so it may be that much shorter
      boolean lastShortBySeparator = i == items.size() - 1 && widths[i] + HaxeJoinedLine.SEPARATOR_WIDTH == widths[0];
      equalWidths &= widths[i] == widths[0] || lastShortBySeparator;
    }

    // a threshold of 0 takes its rule out; the others still apply
    boolean smallTotal = thresholds.keepTotalLength() > 0 && total <= thresholds.keepTotalLength();
    boolean equalItems = thresholds.fillEqualItemLength() > 0 && thresholds.fillEqualItemCount() > 0
                         && equalWidths && longest <= thresholds.fillEqualItemLength() && items.size() >= thresholds.fillEqualItemCount();
    boolean tinyItems = thresholds.fillItemLength() > 0 && thresholds.fillItemCount() > 0
                        && longest <= thresholds.fillItemLength() && items.size() >= thresholds.fillItemCount();
    boolean longItem = thresholds.chopItemLength() > 0 && longest >= thresholds.chopItemLength();
    boolean manyItems = thresholds.chopItemCount() > 0 && items.size() >= thresholds.chopItemCount();
    if (multilineItem) return Decision.ONE_PER_LINE;
    if (smallTotal) return Decision.KEEP;
    if (equalItems || tinyItems) return Decision.FILL_AFTER_LEADING_BREAK;
    if (longItem || manyItems) return Decision.ONE_PER_LINE;
    if (exceedsMargin(literal, common, haxe) && chops(common)) return Decision.ONE_PER_LINE;
    return Decision.KEEP;
  }

  /** The literal's items: the expressions of its item list (a comprehension has none). */
  private static List<ASTNode> items(ASTNode literal) {
    List<ASTNode> items = new ArrayList<>();
    ASTNode list = literal.findChildByType(EXPRESSION_LIST);
    if (list == null) return items;
    for (ASTNode child = list.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (WHITESPACES.contains(type) || COMMENTS.contains(type) || type == OCOMMA) continue;
      items.add(child);
    }
    return items;
  }

  private static boolean exceedsMargin(ASTNode literal, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeWrapLines.lineOf(literal, common, haxe);
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    return line != null && line.width() > margin;
  }

  /** The array wrap setting breaks one per line past the margin (the overflow rule of the tool's list). */
  private static boolean chops(CommonCodeStyleSettings common) {
    return WrappingUtil.getWrapType(common.ARRAY_INITIALIZER_WRAP) == WrapType.CHOP_DOWN_IF_LONG;
  }
}
