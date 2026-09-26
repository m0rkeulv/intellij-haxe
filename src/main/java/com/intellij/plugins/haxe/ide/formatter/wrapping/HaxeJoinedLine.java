package com.intellij.plugins.haxe.ide.formatter.wrapping;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.HaxeIndentText;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
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
public final class HaxeJoinedLine {

  // the containers whose children start their own lines
  public static final TokenSet STATEMENT_CONTAINERS = TokenSet.orSet(
    CLASS_BODY_TYPES,
    TokenSet.create(BLOCK_STATEMENT, SWITCH_CASE_BLOCK, MODULE, PPBODY, INACTIVE_STATEMENT_LIST, INACTIVE_MEMBER_LIST,
                    INACTIVE_MODULE_LIST));

  // the ", " between items, as the tool counts it in item lengths
  public static final int SEPARATOR_WIDTH = 2;

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
  public static HaxeJoinedLine of(@NotNull ASTNode node, @NotNull CommonCodeStyleSettings common, @NotNull HaxeCodeStyleSettings haxe) {
    PsiFile file = node.getPsi().getContainingFile();
    if (file == null) return null;
    CharSequence text = file.getViewProvider().getContents();
    int tabSize = tabSize(common);
    ASTNode statement = statementOf(node);
    // the line starts at its first character, not the statement's: same-line
    // metadata sits beside its declaration in the PSI
    String lineIndent = HaxeIndentText.lineIndentAt(text, statement.getStartOffset());
    int lineStart = HaxeIndentText.lineStartOffset(text, statement.getStartOffset()) + lineIndent.length();
    int indent = HaxeIndentText.indentWidth(lineIndent, tabSize);
    HaxeJoinedLine line = new HaxeJoinedLine(text, lineStart, firstBodyStart(statement), indent);

    // a multi-var the split puts one declarator per line: the node's
    // declarator is its line, later ones a step in
    ASTNode declarator = splitDeclaratorOf(node, statement, common, haxe);
    if (declarator != null) {
      boolean first = declarator.getTreePrev() == null || findPrevious(declarator, LOCAL_VAR_DECLARATION) == null;
      int declaratorStart = first ? lineStart : declarator.getStartOffset();
      int declaratorEnd = declarator.getStartOffset() + declarator.getTextLength();
      line = new HaxeJoinedLine(text, declaratorStart, declaratorEnd, first ? indent : indent + tabSize);
    }

    // an operand of an exploding operator chain starts its own line, its
    // leading operator first, one step in from the chain's line
    ASTNode operand = explodedChainOperandOf(node, statement, common, haxe);
    if (operand != null) {
      HaxeJoinedLine chainLine = of(operand.getTreeParent(), common, haxe);
      ASTNode leadingOperator = previousCode(operand);
      int operandStart = leadingOperator == null ? operand.getStartOffset() : leadingOperator.getStartOffset();
      int chainIndent = chainLine == null ? indent : chainLine.indent;
      line = new HaxeJoinedLine(text, operandStart, operand.getStartOffset() + operand.getTextLength(), chainIndent + tabSize);
    }

    int margin = common.getRootSettings().getRightMargin(HaxeLanguage.INSTANCE);
    boolean chainsChop = common.METHOD_CALL_CHAIN_WRAP != CommonCodeStyleSettings.DO_NOT_WRAP;
    ASTNode link = chainsChop && line.width() > margin ? chainLinkOf(node, statement) : null;
    if (link == null) return line;
    ASTNode dot = link.getFirstChildNode().findChildByType(ODOT);
    int linkStart = dot == null ? link.getStartOffset() : dot.getStartOffset();
    return new HaxeJoinedLine(text, linkStart, link.getStartOffset() + link.getTextLength(), line.indent + tabSize);
  }

  /** The declarator holding the node, when its declaration list splits one per line. */
  @Nullable
  private static ASTNode splitDeclaratorOf(ASTNode node, ASTNode statement, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    for (ASTNode ancestor = node; ancestor != null && ancestor != statement; ancestor = ancestor.getTreeParent()) {
      if (ancestor.getElementType() != LOCAL_VAR_DECLARATION) continue;
      ASTNode list = ancestor.getTreeParent();
      boolean splits = list != null
                       && list.getElementType() == LOCAL_VAR_DECLARATION_LIST
                       && HaxeMultiVarSplit.splits(list, common, haxe);
      return splits ? ancestor : null;
    }
    return null;
  }

  /**
   * The innermost operand holding the node whose operator chain (of either
   * kind) explodes, below the statement; null when no enclosing chain does.
   */
  @Nullable
  private static ASTNode explodedChainOperandOf(ASTNode node, ASTNode statement, CommonCodeStyleSettings common, HaxeCodeStyleSettings haxe) {
    for (ASTNode ancestor = node; ancestor != null && ancestor != statement; ancestor = ancestor.getTreeParent()) {
      ASTNode parent = ancestor.getTreeParent();
      if (parent == null) return null;
      for (HaxeOperatorChainRules.Kind kind : HaxeOperatorChainRules.Kind.values()) {
        boolean operand = kind.chainTypes.contains(parent.getElementType()) && !kind.isOperator(ancestor)
                          && !kind.chainTypes.contains(ancestor.getElementType());
        if (operand && HaxeOperatorChainRules.explodes(kind, parent, common, haxe)) return ancestor;
      }
    }
    return null;
  }

  @Nullable
  private static ASTNode previousCode(ASTNode node) {
    ASTNode previous = node.getTreePrev();
    while (previous != null && (WHITESPACES.contains(previous.getElementType()) || COMMENTS.contains(previous.getElementType()))) {
      previous = previous.getTreePrev();
    }
    return previous;
  }

  @Nullable
  private static ASTNode findPrevious(ASTNode node, IElementType type) {
    for (ASTNode previous = node.getTreePrev(); previous != null; previous = previous.getTreePrev()) {
      if (previous.getElementType() == type) return previous;
    }
    return null;
  }

  /** The tab width the columns count with. */
  static int tabSize(@NotNull CommonCodeStyleSettings common) {
    return HaxeIndentText.indentOptions(common).TAB_SIZE;
  }

  /** The node's width as it prints on one line: every whitespace run one space. */
  static int oneLineWidth(@NotNull ASTNode node) {
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

  /** The line's width when printed. */
  public int width() {
    return indent + collapsedWidth(start, end);
  }

  /** The column the node's first character lands on. */
  public int columnBefore(@NotNull ASTNode node) {
    return indent + collapsedWidth(start, node.getStartOffset());
  }

  /** The column right after the node's last character. */
  public int columnAfter(@NotNull ASTNode node) {
    return indent + collapsedWidth(start, node.getStartOffset() + node.getTextLength());
  }

  public int indent() {
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
