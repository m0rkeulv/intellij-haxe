package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.*;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * The line a node sits on the way haxe-formatter judges its wrap rules: the
 * statement printed on ONE line (every whitespace run a single space), from
 * the point where the tool's earlier wraps leave it - the statement's start,
 * or the start of a chopped method-chain link when the statement overflows
 * the margin and the node lies inside such a link. Columns count the line's
 * indent in tab-expanded width.
 */
final class HaxeJoinedLine {

  // the containers whose children start their own lines
  static final TokenSet STATEMENT_CONTAINERS = TokenSet.orSet(
    CLASS_BODY_TYPES,
    TokenSet.create(BLOCK_STATEMENT, SWITCH_CASE_BLOCK, MODULE, PPBODY, INACTIVE_STATEMENT_LIST, INACTIVE_MEMBER_LIST,
                    INACTIVE_MODULE_LIST));

  private final CharSequence text;
  private final int start;
  private final int end;
  private final int indent;

  private HaxeJoinedLine(CharSequence text, int start, int end, int indent) {
    this.text = text;
    this.start = start;
    this.end = end;
    this.indent = indent;
  }

  @Nullable
  static HaxeJoinedLine of(@NotNull ASTNode node, @NotNull CommonCodeStyleSettings common) {
    PsiFile file = node.getPsi().getContainingFile();
    if (file == null) return null;
    CharSequence text = file.getViewProvider().getContents();
    int tabSize = common.getIndentOptions() == null ? 4 : common.getIndentOptions().TAB_SIZE;
    ASTNode statement = statementOf(node);
    // the line starts at its first character, not the statement's: same-line
    // metadata sits beside its declaration in the PSI
    String lineIndent = HaxeIndentText.lineIndentAt(text, statement.getStartOffset());
    int lineStart = HaxeIndentText.lineStartOffset(text, statement.getStartOffset()) + lineIndent.length();
    int indent = HaxeIndentText.indentWidth(lineIndent, tabSize);
    HaxeJoinedLine statementLine = new HaxeJoinedLine(text, lineStart, firstBodyStart(statement), indent);

    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    boolean chainsChop = common.METHOD_CALL_CHAIN_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP;
    ASTNode link = chainsChop && statementLine.width() > margin ? chainLinkOf(node, statement) : null;
    if (link == null) return statementLine;
    ASTNode dot = link.getFirstChildNode().findChildByType(ODOT);
    int linkStart = dot == null ? link.getStartOffset() : dot.getStartOffset();
    return new HaxeJoinedLine(text, linkStart, link.getStartOffset() + link.getTextLength(), indent + tabSize);
  }

  /** The line's width when printed. */
  int width() {
    return indent + collapsedWidth(start, end);
  }

  /** The column the node's first character lands on. */
  int columnBefore(@NotNull ASTNode node) {
    return indent + collapsedWidth(start, node.getStartOffset());
  }

  /** The column right after the node's last character. */
  int columnAfter(@NotNull ASTNode node) {
    return indent + collapsedWidth(start, node.getStartOffset() + node.getTextLength());
  }

  int indent() {
    return indent;
  }

  private static ASTNode statementOf(ASTNode node) {
    ASTNode statement = node;
    while (statement.getTreeParent() != null && !STATEMENT_CONTAINERS.contains(statement.getTreeParent().getElementType())) {
      statement = statement.getTreeParent();
    }
    return statement;
  }

  /** Where the statement's first line ends when joined: at its first block body, else at its end. */
  private static int firstBodyStart(ASTNode statement) {
    ASTNode body = firstDescendant(statement, BLOCK_STATEMENT);
    return body == null ? statement.getStartOffset() + statement.getTextLength() : body.getStartOffset();
  }

  @Nullable
  private static ASTNode firstDescendant(ASTNode node, IElementType type) {
    for (ASTNode child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      if (child.getElementType() == type) return child;
      ASTNode inner = firstDescendant(child, type);
      if (inner != null) return inner;
    }
    return null;
  }

  /**
   * The innermost chained call link ({@code .link(...)} whose receiver is
   * itself a call) holding the node, below the statement; a chopped chain
   * starts every such link on its own line.
   */
  @Nullable
  private static ASTNode chainLinkOf(ASTNode node, ASTNode statement) {
    for (ASTNode call = node; call != null && call != statement; call = call.getTreeParent()) {
      if (call.getElementType() != CALL_EXPRESSION) continue;
      ASTNode reference = call.getFirstChildNode();
      boolean chainedLink = reference != null
                            && reference.getElementType() == REFERENCE_EXPRESSION
                            && reference.getFirstChildNode() != null
                            && reference.getFirstChildNode().getElementType() == CALL_EXPRESSION;
      if (chainedLink) return call;
    }
    return null;
  }

  /** The width of the text as it prints on one line: every whitespace run one space. */
  private int collapsedWidth(int from, int to) {
    int width = 0;
    boolean inWhitespace = false;
    for (int i = from; i < to; i++) {
      boolean whitespace = Character.isWhitespace(text.charAt(i));
      if (!whitespace || !inWhitespace) width++;
      inWhitespace = whitespace;
    }
    return width;
  }
}
