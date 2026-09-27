package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes.hasStatementBody;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.ARGUMENT_LISTS;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.FUNCTION_LIKE_OWNERS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's fillLine for a signature (functionSignature,
 * anonFunctionSignature), a call, a new and an enum constructor
 * (callParameter), decided on the joined line - written breaks between the
 * items do not count. The tool's accounting:
 * <ul>
 * <li>an item counts its printed text (type hint, {@code ?} marker and
 * default value included) plus the {@code ", "} after it; the last item
 * counts no separator;</li>
 * <li>an item that would REACH the margin (column + width >= margin) starts a
 * continuation line: one step in for a call, a new, an enum constructor or a
 * function without a body (none, or an empty {@code {}}), two for a
 * function with a body;</li>
 * <li>the last item also moves when what follows it on the line - the
 * closing paren, a return hint, {@code {}, {@code ;}, a chained call - PASSES
 * the margin (column + width + rest > margin);</li>
 * <li>the first item never moves, but when it reaches the margin the count
 * restarts at the continuation column as if it had; a line still past the
 * margin after that re-packs greedily (an item that reaches the margin with
 * its comma but without the trailing space moves down).</li>
 * </ul>
 * A list's moved items are memoized on it ({@link HaxeWrapMemo}).
 * <p>
 * TODO: an argument the tool prints over several lines (a lambda with a block
 *       body) counts here as one line; the tool counts its first line and
 *       restarts after its last.
 */
public final class HaxeCallArgumentFill {

  private static final Key<HaxeWrapMemo.Entry<List<ASTNode>>> BROKEN_ARGUMENTS_MEMO = Key.create("HaxeCallArgumentFill.brokenArguments");

  private HaxeCallArgumentFill() {
  }

  /** The arguments of the list that start a new line; empty when the joined line fits the margin. */
  @NotNull
  public static List<ASTNode> brokenArguments(@NotNull ASTNode list, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    return HaxeWrapMemo.cached(list, BROKEN_ARGUMENTS_MEMO, common, haxe, () -> computeBrokenArguments(list, common, haxe));
  }

  private static List<ASTNode> computeBrokenArguments(ASTNode list, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    HaxeJoinedLine line = HaxeWrapLines.lineOf(list, common, haxe);
    if (line == null) return List.of();
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    if (line.width() < margin) return List.of();
    List<ASTNode> arguments = arguments(list);
    if (arguments.size() < 2) return List.of();

    int continuation = line.indent() + continuationSteps(list) * HaxeJoinedLine.tabSize(common);
    int trailing = line.widthAfter(arguments.getLast());
    Fill fill = new Fill(arguments, itemWidths(arguments), line.columnBefore(arguments.getFirst()), continuation, trailing, margin);
    Set<ASTNode> broken = new LinkedHashSet<>();
    int parenLineItems = fill.fillPass(broken);
    fill.longLinePass(broken, parenLineItems);
    return List.copyOf(broken);
  }

  /** Each item's width as the tool counts it: its printed text plus the ", " after it, none after the last. */
  private static int[] itemWidths(List<ASTNode> arguments) {
    int[] widths = new int[arguments.size()];
    for (int i = 0; i < widths.length; i++) {
      int separator = i < widths.length - 1 ? HaxeJoinedLine.SEPARATOR_WIDTH : 0;
      widths[i] = HaxeJoinedLine.oneLineWidth(arguments.get(i)) + separator;
    }
    return widths;
  }

  /** The tool's continuation for a moved item: two steps under a function with statements, else one. */
  private static int continuationSteps(ASTNode list) {
    ASTNode owner = list.getElementType() == NEW_EXPRESSION ? list : list.getTreeParent();
    boolean function = owner != null && FUNCTION_LIKE_OWNERS.contains(owner.getElementType());
    return function && hasStatementBody(owner) ? 2 : 1;
  }

  /** The list's arguments: its expression children ({@code new T(a, b)} keeps them as direct children of the expression). */
  private static List<ASTNode> arguments(ASTNode list) {
    List<ASTNode> arguments = new ArrayList<>();
    boolean inParens = false;
    for (ASTNode child = list.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      IElementType type = child.getElementType();
      if (list.getElementType() == NEW_EXPRESSION) {
        if (type == PLPAREN) inParens = true;
        if (!inParens || type == PLPAREN || type == PRPAREN) continue;
      }
      if (WHITESPACES.contains(type) || COMMENTS.contains(type) || type == OCOMMA) continue;
      arguments.add(child);
    }
    return arguments;
  }

  /**
   * The list the node is or belongs to when that list fills: an argument
   * list under a call, a signature or an enum constructor, or the
   * new-expression holding its arguments directly (functionSignature fills
   * the same way); null for a list that does not fill.
   */
  @Nullable
  public static ASTNode filledListOf(@NotNull ASTNode node) {
    ASTNode list = ARGUMENT_LISTS.contains(node.getElementType()) ? node : node.getTreeParent();
    if (list == null) return null;
    if (list.getElementType() == NEW_EXPRESSION) return list;
    if (!ARGUMENT_LISTS.contains(list.getElementType())) return null;
    ASTNode owner = list.getTreeParent();
    if (owner == null) return null;
    IElementType ownerType = owner.getElementType();
    boolean filled = ownerType == CALL_EXPRESSION
                     || ownerType == NEW_EXPRESSION
                     || ownerType == ENUM_VALUE_DECLARATION_CONSTRUCTOR
                     || FUNCTION_LIKE_OWNERS.contains(ownerType);
    return filled ? list : null;
  }

  /**
   * One list's fill inputs: its items, their widths, the column after the
   * opening paren, the continuation column, the width printed after the last
   * item and the margin.
   */
  private record Fill(List<ASTNode> arguments, int[] widths, int parenColumn, int continuation, int trailing, int margin) {

    /** The fill pass; returns how many items stay on the paren's line. */
    int fillPass(Set<ASTNode> broken) {
      int last = arguments.size() - 1;
      int parenLineItems = arguments.size();
      int column = parenColumn;
      for (int i = 0; i <= last; i++) {
        if (column + widths[i] < margin) {
          column += widths[i];
          continue;
        }
        column = continuation + widths[i];
        if (i > 0) {
          broken.add(arguments.get(i));
          parenLineItems = Math.min(parenLineItems, i);
        }
      }
      if (column + trailing > margin) {
        broken.add(arguments.get(last));
        parenLineItems = Math.min(parenLineItems, last);
      }
      return parenLineItems;
    }

    /** The re-pack of a paren line still past the margin (its first item alone reached it). */
    void longLinePass(Set<ASTNode> broken, int parenLineItems) {
      int last = arguments.size() - 1;
      boolean listEnds = parenLineItems == arguments.size();
      int width = parenColumn + (listEnds ? trailing : -1);
      for (int i = 0; i < parenLineItems; i++) width += widths[i];
      if (width <= margin) return;

      int column = parenColumn + widths[0];
      for (int i = 1; i < parenLineItems; i++) {
        int part = i == last ? widths[i] + trailing : widths[i] - 1;
        if (column + part >= margin) {
          broken.add(arguments.get(i));
          column = continuation;
        }
        column += widths[i];
      }
    }
  }
}
