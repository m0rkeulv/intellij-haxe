package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.ARGUMENT_LISTS;
import static com.intellij.plugins.haxe.ide.formatter.HaxeFormatterTokenSets.FUNCTION_LIKE_OWNERS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * haxe-formatter's callParameter fillLine, decided on the joined line: the
 * arguments fill from the opening paren, and an argument (its trailing
 * comma and space included) that would reach the margin starts a new line
 * one step in; the first argument stays on the paren's line.
 */
public final class HaxeCallArgumentFill {

  private HaxeCallArgumentFill() {
  }

  /** The arguments of the list that start a new line; empty when the joined line fits the margin. */
  @NotNull
  public static List<ASTNode> brokenArguments(@NotNull ASTNode list, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    List<ASTNode> broken = new ArrayList<>();
    HaxeJoinedLine line = HaxeJoinedLine.of(list, common, haxe);
    if (line == null) return broken;
    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    if (line.width() < margin) return broken;
    int tabSize = HaxeJoinedLine.tabSize(common);

    List<ASTNode> arguments = arguments(list);
    if (arguments.isEmpty()) return broken;
    // the first argument stays on the paren's line however long it is
    int column = line.columnAfter(arguments.getFirst()) + HaxeJoinedLine.SEPARATOR_WIDTH;
    for (int i = 1; i < arguments.size(); i++) {
      ASTNode argument = arguments.get(i);
      int width = HaxeJoinedLine.oneLineWidth(argument) + (i < arguments.size() - 1 ? HaxeJoinedLine.SEPARATOR_WIDTH : 0);
      if (column + width >= margin) {
        broken.add(argument);
        column = line.indent() + tabSize + width;
      }
      else {
        column += width;
      }
    }
    return broken;
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
   * The list a call argument or a declared parameter belongs to: an
   * argument-list node under a call, a signature or an enum constructor, or
   * the new-expression holding it (functionSignature fills the same way).
   */
  @Nullable
  public static ASTNode listOf(@NotNull ASTNode argument) {
    ASTNode parent = argument.getTreeParent();
    if (parent == null) return null;
    if (parent.getElementType() == NEW_EXPRESSION) return parent;
    if (!ARGUMENT_LISTS.contains(parent.getElementType())) return null;
    ASTNode owner = parent.getTreeParent();
    if (owner == null) return null;
    IElementType ownerType = owner.getElementType();
    boolean filled = ownerType == CALL_EXPRESSION
                     || ownerType == NEW_EXPRESSION
                     || ownerType == ENUM_VALUE_DECLARATION_CONSTRUCTOR
                     || FUNCTION_LIKE_OWNERS.contains(ownerType);
    return filled ? parent : null;
  }
}
