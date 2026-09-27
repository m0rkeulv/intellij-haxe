package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.HaxeFormatterNodes;
import com.intellij.plugins.haxe.ide.formatter.HaxeIndentText;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
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
 * the point where the tool's earlier wraps leave it. A {@link Context} fixes
 * the statement and its indent; each refinement narrows the line to the
 * piece an earlier wrap starts on its own line - {@link HaxeWrapLines}
 * picks the refinements the rules ask for. Columns count the line's indent
 * in tab-expanded width.
 */
final class HaxeJoinedLine {

  // the containers whose children start their own lines
  static final TokenSet STATEMENT_CONTAINERS = TokenSet.orSet(
    CLASS_BODY_TYPES,
    TokenSet.create(BLOCK_STATEMENT, SWITCH_CASE_BLOCK, MODULE, PPBODY, INACTIVE_STATEMENT_LIST, INACTIVE_MEMBER_LIST,
                    INACTIVE_MODULE_LIST));

  // the ", " between items, as the tool counts it in item lengths
  static final int SEPARATOR_WIDTH = 2;

  private final CharSequence text;
  private final int start;
  private final int end;
  private final int indent;
  // the line stops where a block body opens: its brace still prints on the line
  private final boolean bodyFollows;

  private HaxeJoinedLine(CharSequence text, int start, int end, int indent, boolean bodyFollows) {
    this.text = text;
    this.start = start;
    this.end = end;
    this.indent = indent;
    this.bodyFollows = bodyFollows;
  }

  /** The line's width when printed. */
  int width() {
    return indent + oneLineWidth(text, start, end);
  }

  /** The width printed past the node's last character: the rest of the line, the opening brace of a body the line stops at included. */
  int widthAfter(@NotNull ASTNode node) {
    return width() - columnAfter(node) + (bodyFollows ? 1 : 0);
  }

  /** The column the node's first character lands on. */
  int columnBefore(@NotNull ASTNode node) {
    return indent + oneLineWidth(text, start, node.getStartOffset());
  }

  /** The column right after the node's last character. */
  int columnAfter(@NotNull ASTNode node) {
    return indent + oneLineWidth(text, start, node.getTextRange().getEndOffset());
  }

  int indent() {
    return indent;
  }

  /** The tab width the columns count with. */
  static int tabSize(@NotNull CommonCodeStyleSettings common) {
    return HaxeIndentText.indentOptions(common).TAB_SIZE;
  }

  /** The node's width as it prints on one line: every whitespace run one space. */
  static int oneLineWidth(@NotNull ASTNode node) {
    CharSequence chars = node.getChars();
    return oneLineWidth(chars, 0, chars.length());
  }

  /** The width of the text between the offsets as it prints on one line: every whitespace run one space. */
  private static int oneLineWidth(CharSequence chars, int from, int to) {
    int width = 0;
    boolean inWhitespace = false;
    for (int i = from; i < to; i++) {
      boolean whitespace = Character.isWhitespace(chars.charAt(i));
      if (!whitespace || !inWhitespace) width++;
      inWhitespace = whitespace;
    }
    return width;
  }

  /**
   * What every line of a node shares: the file text, the statement the node
   * belongs to, the offset and tab-expanded indent of the statement's first
   * character, the margin and whether method chains chop.
   */
  record Context(CharSequence text, int tabSize, int margin, boolean chainsChop,
                 ASTNode statement, int lineStart, int indent, CommonCodeStyleSettings common) {

    @Nullable
    static Context of(@NotNull ASTNode node, @NotNull CommonCodeStyleSettings common) {
      PsiFile file = node.getPsi().getContainingFile();
      if (file == null) return null;
      CharSequence text = file.getViewProvider().getContents();
      int tabSize = HaxeJoinedLine.tabSize(common);
      int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
      boolean chainsChop = common.METHOD_CALL_CHAIN_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP;
      ASTNode statement = statementOf(node);
      // the line starts at its first character, not the statement's: same-line
      // metadata sits beside its declaration in the PSI
      String lineIndent = HaxeIndentText.lineIndentAt(text, statement.getStartOffset());
      int lineStart = HaxeIndentText.lineStartOffset(text, statement.getStartOffset()) + lineIndent.length();
      int indent = HaxeIndentText.indentWidth(lineIndent, tabSize);
      return new Context(text, tabSize, margin, chainsChop, statement, lineStart, indent, common);
    }

    /**
     * The statement's first line: up to its first block body, else its end.
     * The body's brace prints on the line only under an end-of-line brace
     * style; a next-line style ends the line at the last character before it.
     */
    HaxeJoinedLine statementLine() {
      ASTNode body = firstDescendant(statement, BLOCK_STATEMENT);
      if (body == null) return new HaxeJoinedLine(text, lineStart, statement.getTextRange().getEndOffset(), indent, false);
      boolean braceOnLine = !HaxeFormatterNodes.nextLineBraces(common, body);
      int end = braceOnLine ? body.getStartOffset() : endBeforeWhitespace(body.getStartOffset());
      return new HaxeJoinedLine(text, lineStart, end, indent, braceOnLine);
    }

    private int endBeforeWhitespace(int offset) {
      int end = offset;
      while (end > lineStart && Character.isWhitespace(text.charAt(end - 1))) end--;
      return end;
    }

    /** A declarator's line once its multi-var splits one per line: the first keeps the statement's start, later ones step in. */
    HaxeJoinedLine declaratorLine(@NotNull ASTNode declarator) {
      boolean first = declarator.getTreePrev() == null || findPrevious(declarator, LOCAL_VAR_DECLARATION) == null;
      int declaratorStart = first ? lineStart : declarator.getStartOffset();
      int declaratorEnd = declarator.getTextRange().getEndOffset();
      return new HaxeJoinedLine(text, declaratorStart, declaratorEnd, first ? indent : indent + tabSize, false);
    }

    /** An exploded chain operand's line: its leading operator first, one step in from the chain's indent. */
    HaxeJoinedLine operandLine(@NotNull ASTNode operand, int chainIndent) {
      ASTNode leadingOperator = UsefulPsiTreeUtil.getPrevSiblingSkipWhiteSpacesAndComments(operand);
      int operandStart = leadingOperator == null ? operand.getStartOffset() : leadingOperator.getStartOffset();
      return new HaxeJoinedLine(text, operandStart, operand.getTextRange().getEndOffset(), chainIndent + tabSize, false);
    }

    /**
     * The line cut down to the chopped method-chain link holding the node -
     * when chains chop and the line overflows the margin; else the line as is.
     */
    HaxeJoinedLine choppedLinkLine(@NotNull HaxeJoinedLine line, @NotNull ASTNode node) {
      ASTNode link = chainsChop && line.width() > margin ? chainLinkOf(node, statement) : null;
      if (link == null) return line;
      ASTNode dot = link.getFirstChildNode().findChildByType(ODOT);
      int linkStart = dot == null ? link.getStartOffset() : dot.getStartOffset();
      return new HaxeJoinedLine(text, linkStart, link.getTextRange().getEndOffset(), line.indent + tabSize, false);
    }
  }

  private static ASTNode statementOf(ASTNode node) {
    ASTNode statement = node;
    while (statement.getTreeParent() != null && !STATEMENT_CONTAINERS.contains(statement.getTreeParent().getElementType())) {
      statement = statement.getTreeParent();
    }
    return statement;
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

  @Nullable
  private static ASTNode findPrevious(ASTNode node, IElementType type) {
    for (ASTNode previous = node.getTreePrev(); previous != null; previous = previous.getTreePrev()) {
      if (previous.getElementType() == type) return previous;
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
      if (call.getElementType() == CALL_EXPRESSION && HaxeFormatterNodes.isChainLink(call.getFirstChildNode())) return call;
    }
    return null;
  }
}
