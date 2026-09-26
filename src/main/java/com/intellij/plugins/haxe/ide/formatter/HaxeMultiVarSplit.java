package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.LOCAL_VAR_DECLARATION;

/**
 * hxformat's wrapping.multiVar split: a declaration list whose joined line
 * passes the split width goes one declarator per line - unless a declarator
 * is short enough, as the tool measures it, to keep the list filling (its
 * anyItemLength rule precedes the split rule).
 */
final class HaxeMultiVarSplit {

  // the tool measures a declarator with its trailing comma or semicolon; the
  // first one also carries the var keyword's gap
  private static final int DECLARATOR_EXTRA = 1;
  private static final int FIRST_DECLARATOR_EXTRA = 2;

  private HaxeMultiVarSplit() {
  }

  static boolean splits(@NotNull ASTNode declarationList, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    int splitWidth = haxe.MULTI_VAR_SPLIT_WIDTH;
    if (splitWidth <= 0) return false;
    int fillItemLength = haxe.MULTI_VAR_FILL_ITEM_LENGTH;
    if (fillItemLength > 0 && shortestDeclaratorLength(declarationList) <= fillItemLength) return false;
    PsiFile file = declarationList.getPsi().getContainingFile();
    if (file == null) return false;
    // the statement's post-format line indent matches its current one in all
    // but pathological inputs - good enough for a width heuristic
    CharSequence text = file.getViewProvider().getContents();
    int tabSize = HaxeJoinedLine.tabSize(common);
    int indentColumns = HaxeIndentText.indentWidth(HaxeIndentText.lineIndentAt(text, declarationList.getStartOffset()), tabSize);
    return indentColumns + HaxeJoinedLine.oneLineWidth(declarationList) >= splitWidth;
  }

  /**
   * The shortest declarator as haxe-formatter measures its multiVar items:
   * each with its trailing comma or semicolon, the first one two wider (its
   * item starts at the var keyword's gap).
   */
  private static int shortestDeclaratorLength(@NotNull ASTNode declarationList) {
    int shortest = Integer.MAX_VALUE;
    boolean first = true;
    for (ASTNode child = declarationList.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child.getElementType() != LOCAL_VAR_DECLARATION) continue;
      int trailing = first ? FIRST_DECLARATOR_EXTRA : DECLARATOR_EXTRA;
      shortest = Math.min(shortest, HaxeJoinedLine.oneLineWidth(child) + trailing);
      first = false;
    }
    return shortest == Integer.MAX_VALUE ? 0 : shortest;
  }
}
