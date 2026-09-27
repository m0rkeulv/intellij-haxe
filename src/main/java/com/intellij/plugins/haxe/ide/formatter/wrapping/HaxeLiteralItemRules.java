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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.COMMENTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.WHITESPACES;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's item rules for array, map and object literals
 * (wrapping.arrayWrap, mapWrap and objectLiteral), decided the way the tool
 * decides them on the literal's items - each counting the ", " after it,
 * none after the last - and its joined line. First match wins; a kind's
 * thresholds that are not configurable in the tool sit at 0 and take their
 * rule out:
 *
 * <pre>
 * an item written over several lines (arrays, maps) / the literal written over lines (objects) -> ONE_PER_LINE
 * up to KEEP_ITEM_COUNT items on a line within the margin                                       -> KEEP
 * items totalling at most KEEP_TOTAL_LENGTH                                                     -> KEEP
 * FILL_EQUAL_ITEM_COUNT items or more of one length, at most FILL_EQUAL_ITEM_LENGTH             -> FILL_AFTER_LEADING_BREAK
 * FILL_ITEM_COUNT items or more, each at most FILL_ITEM_LENGTH                                  -> FILL_AFTER_LEADING_BREAK
 * an item reaching CHOP_ITEM_LENGTH                                                             -> ONE_PER_LINE
 * items totalling CHOP_TOTAL_LENGTH or more                                                     -> ONE_PER_LINE
 * CHOP_ITEM_COUNT items or more                                                                 -> ONE_PER_LINE
 * the line past the margin under a chopping array wrap setting                                  -> ONE_PER_LINE
 * otherwise                                                                                     -> KEEP
 * </pre>
 *
 * Items are of one length when every width matches, the last item allowed
 * to be its separator short. A threshold of 0 takes its rule out; a kind
 * with every threshold at 0 leaves its literals to the array wrap setting
 * alone ({@link Decision#NONE}). A literal's decision is memoized on it
 * ({@link HaxeWrapMemo}).
 */
public final class HaxeLiteralItemRules {

  public enum Decision { NONE, KEEP, ONE_PER_LINE, FILL_AFTER_LEADING_BREAK }

  /** The literal kinds with item rules, each reading its own thresholds and collecting its own items. */
  public enum Kind {
    ARRAY, MAP, OBJECT;

    /** The kind of the literal node; null for any other node. */
    @Nullable
    public static Kind ofLiteral(@NotNull ASTNode node) {
      IElementType type = node.getElementType();
      if (type == ARRAY_LITERAL) return ARRAY;
      if (type == MAP_LITERAL) return MAP;
      if (type == OBJECT_LITERAL) return OBJECT;
      return null;
    }

    Thresholds thresholds(@NotNull HaxeCodeStyleSettings haxe) {
      return switch (this) {
        case ARRAY -> new Thresholds(0, haxe.ARRAY_KEEP_TOTAL_LENGTH,
                                     haxe.ARRAY_FILL_EQUAL_ITEM_LENGTH, haxe.ARRAY_FILL_EQUAL_ITEM_COUNT,
                                     haxe.ARRAY_FILL_ITEM_LENGTH, haxe.ARRAY_FILL_ITEM_COUNT,
                                     haxe.ARRAY_CHOP_ITEM_LENGTH, 0, haxe.ARRAY_CHOP_ITEM_COUNT);
        case MAP -> new Thresholds(0, haxe.MAP_KEEP_TOTAL_LENGTH,
                                   haxe.MAP_FILL_EQUAL_ITEM_LENGTH, haxe.MAP_FILL_EQUAL_ITEM_COUNT,
                                   haxe.MAP_FILL_ITEM_LENGTH, haxe.MAP_FILL_ITEM_COUNT,
                                   haxe.MAP_CHOP_ITEM_LENGTH, 0, haxe.MAP_CHOP_ITEM_COUNT);
        case OBJECT -> new Thresholds(haxe.OBJECT_KEEP_ITEM_COUNT, 0, 0, 0, 0, 0,
                                      haxe.OBJECT_CHOP_ITEM_LENGTH, haxe.OBJECT_CHOP_TOTAL_LENGTH, haxe.OBJECT_CHOP_ITEM_COUNT);
      };
    }

    /** The literal's items: the expressions, entries or fields between its brackets; none for a comprehension or an empty literal. */
    List<ASTNode> items(@NotNull ASTNode literal) {
      ASTNode holder = switch (this) {
        case ARRAY -> literal.findChildByType(EXPRESSION_LIST);
        case MAP -> literal.findChildByType(MAP_INITIALIZER_EXPRESSION_LIST);
        case OBJECT -> literal;
      };
      List<ASTNode> items = new ArrayList<>();
      if (holder == null) return items;
      for (ASTNode child = holder.getFirstChildNode(); child != null; child = child.getTreeNext()) {
        IElementType type = child.getElementType();
        boolean punctuation = type == OCOMMA || type == PLCURLY || type == PRCURLY;
        if (WHITESPACES.contains(type) || COMMENTS.contains(type) || punctuation) continue;
        items.add(child);
      }
      return items;
    }

    /** Arrays and maps chop on an item written over lines; an object chops when the literal itself was. */
    boolean writtenOverLines(@NotNull ASTNode literal, @NotNull List<ASTNode> items) {
      if (this == OBJECT) return literal.textContains('\n');
      return items.stream().anyMatch(item -> item.textContains('\n'));
    }
  }

  /** One kind's thresholds as configured; a threshold of 0 takes its rule out. */
  record Thresholds(int keepItemCount, int keepTotalLength,
                    int fillEqualItemLength, int fillEqualItemCount, int fillItemLength, int fillItemCount,
                    int chopItemLength, int chopTotalLength, int chopItemCount) {
    boolean allOff() {
      return keepItemCount <= 0 && keepTotalLength <= 0
             && fillEqualItemLength <= 0 && fillEqualItemCount <= 0 && fillItemLength <= 0 && fillItemCount <= 0
             && chopItemLength <= 0 && chopTotalLength <= 0 && chopItemCount <= 0;
    }
  }

  /** The three kinds' thresholds together, the memo's settings input. */
  record AllThresholds(Thresholds array, Thresholds map, Thresholds object) {
    static AllThresholds of(@NotNull HaxeCodeStyleSettings haxe) {
      return new AllThresholds(Kind.ARRAY.thresholds(haxe), Kind.MAP.thresholds(haxe), Kind.OBJECT.thresholds(haxe));
    }
  }

  /** The items' measurements the rules read. */
  private record Items(int count, int total, int longest, boolean equalWidths) {
  }

  private static final Key<HaxeWrapMemo.Entry<Decision>> DECISION_MEMO = Key.create("HaxeLiteralItemRules.decision");

  private HaxeLiteralItemRules() {
  }

  /** The literal's decision; NONE for a node that is no literal of a kind with rules, a comprehension or an empty literal. */
  @NotNull
  public static Decision decide(@NotNull ASTNode literal, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    Kind kind = Kind.ofLiteral(literal);
    if (kind == null) return Decision.NONE;
    return HaxeWrapMemo.cached(literal, DECISION_MEMO, common, haxe, () -> decideLiteral(kind, literal, common, haxe));
  }

  private static Decision decideLiteral(Kind kind, ASTNode literal, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    Thresholds t = kind.thresholds(haxe);
    if (t.allOff()) return Decision.NONE;
    List<ASTNode> itemNodes = kind.items(literal);
    if (itemNodes.isEmpty()) return Decision.NONE;
    Items items = measure(itemNodes);
    boolean exceeds = exceedsMargin(literal, common, haxe);

    // a threshold of 0 takes its rule out; the others still apply
    boolean fewItems = t.keepItemCount() > 0 && items.count() <= t.keepItemCount() && !exceeds;
    boolean smallTotal = t.keepTotalLength() > 0 && items.total() <= t.keepTotalLength();
    boolean equalItems = t.fillEqualItemLength() > 0 && t.fillEqualItemCount() > 0
                         && items.equalWidths() && items.longest() <= t.fillEqualItemLength() && items.count() >= t.fillEqualItemCount();
    boolean tinyItems = t.fillItemLength() > 0 && t.fillItemCount() > 0
                        && items.longest() <= t.fillItemLength() && items.count() >= t.fillItemCount();
    boolean longItem = t.chopItemLength() > 0 && items.longest() >= t.chopItemLength();
    boolean longTotal = t.chopTotalLength() > 0 && items.total() >= t.chopTotalLength();
    boolean manyItems = t.chopItemCount() > 0 && items.count() >= t.chopItemCount();
    if (kind.writtenOverLines(literal, itemNodes)) return Decision.ONE_PER_LINE;
    if (fewItems || smallTotal) return Decision.KEEP;
    if (equalItems || tinyItems) return Decision.FILL_AFTER_LEADING_BREAK;
    if (longItem || longTotal || manyItems) return Decision.ONE_PER_LINE;
    if (exceeds && chops(common)) return Decision.ONE_PER_LINE;
    return Decision.KEEP;
  }

  /** Each item's printed width plus the ", " after it (none after the last), summed and compared. */
  private static Items measure(List<ASTNode> items) {
    int total = 0;
    int longest = 0;
    boolean equalWidths = true;
    int first = 0;
    for (int i = 0; i < items.size(); i++) {
      int separator = i < items.size() - 1 ? HaxeJoinedLine.SEPARATOR_WIDTH : 0;
      int width = HaxeJoinedLine.oneLineWidth(items.get(i)) + separator;
      if (i == 0) first = width;
      total += width;
      longest = Math.max(longest, width);
      // the last item carries no separator, so it may be that much shorter
      boolean lastShortBySeparator = i == items.size() - 1 && width + HaxeJoinedLine.SEPARATOR_WIDTH == first;
      equalWidths &= width == first || lastShortBySeparator;
    }
    return new Items(items.size(), total, longest, equalWidths);
  }

  private static boolean exceedsMargin(ASTNode literal, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeWrapLines.lineOf(literal, common, haxe);
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    return line != null && line.width() > margin;
  }

  /** The array wrap setting breaks one per line past the margin (the overflow rule of the tool's lists). */
  private static boolean chops(CommonCodeStyleSettings common) {
    return WrappingUtil.getWrapType(common.ARRAY_INITIALIZER_WRAP) == WrapType.CHOP_DOWN_IF_LONG;
  }
}
