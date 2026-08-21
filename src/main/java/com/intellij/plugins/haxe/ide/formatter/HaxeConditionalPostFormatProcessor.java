package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.HaxeFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.PPBODY;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * Re-indents the INACTIVE branches of #if/#elseif/#else regions after a
 * reformat. The lexer folds an inactive branch - its code AND the line indent
 * of the directive that follows it - into one PPBODY token, so block
 * formatting cannot reach inside; this pass aligns each such token's lines
 * with the region's nearest preceding directive, preserving the branch's own
 * relative nesting. Single-line (inline expression) regions are untouched.
 *
 * TODO: inactive code is shifted as a group, never re-formatted - a
 *  statement nested in an unparsed branch keeps whatever relative indent it
 *  was written with. True formatting needs the inactive branches parsed;
 *  until then the option defaults to off.
 */
public class HaxeConditionalPostFormatProcessor implements PostFormatProcessor {

  private static final TokenSet PP_DIRECTIVES = TokenSet.create(PPIF, PPELSEIF, PPELSE);
  private static final TokenSet PP_LEAVES = TokenSet.create(PPIF, PPELSEIF, PPELSE, PPBODY);

  @Override
  public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
    if (source instanceof HaxeFile file) {
      processText(file, file.getTextRange(), settings);
    }
    return source;
  }

  @Override
  public @NotNull TextRange processText(@NotNull PsiFile source, @NotNull TextRange rangeToReformat, @NotNull CodeStyleSettings settings) {
    if (!(source instanceof HaxeFile)) return rangeToReformat;
    if (!settings.getCustomSettings(HaxeCodeStyleSettings.class).ALIGN_INACTIVE_CONDITIONAL_BRANCHES) return rangeToReformat;
    Document document = source.getViewProvider().getDocument();
    if (document == null) return rangeToReformat;

    List<ASTNode> ppLeaves = SyntaxTraverser.astTraverser(source.getNode())
      .filter(node -> PP_LEAVES.contains(node.getElementType()))
      .toList();
    if (ppLeaves.isEmpty()) return rangeToReformat;

    CommonCodeStyleSettings.IndentOptions indent = settings.getIndentOptions(HaxeFileType.INSTANCE);
    String original = document.getText();
    StringBuilder working = new StringBuilder(original);

    // leaves are visited in file order, so a blob's rewrite fixes the line
    // indent of the directive AFTER it before that directive is read as the
    // next blob's alignment target
    int shift = 0;
    String target = null;
    for (ASTNode leaf : ppLeaves) {
      IElementType type = leaf.getElementType();
      int start = leaf.getStartOffset() + shift;
      if (PP_DIRECTIVES.contains(type)) {
        target = lineIndentAt(working, start);
        continue;
      }
      boolean inRange = rangeToReformat.intersects(leaf.getStartOffset(), leaf.getStartOffset() + leaf.getTextLength());
      if (target == null || !inRange) continue;
      String blob = working.substring(start, start + leaf.getTextLength());
      String reindented = reindentBlob(blob, target, indent);
      working.replace(start, start + blob.length(), reindented);
      shift += reindented.length() - blob.length();
    }

    if (shift == 0 && working.toString().equals(original)) return rangeToReformat;
    document.replaceString(0, original.length(), working);
    PsiDocumentManager.getInstance(source.getProject()).commitDocument(document);
    int end = Math.min(rangeToReformat.getEndOffset() + shift, working.length());
    return new TextRange(rangeToReformat.getStartOffset(), Math.max(rangeToReformat.getStartOffset(), end));
  }

  /** The whitespace prefix of the line containing {@code offset}. */
  private static String lineIndentAt(StringBuilder text, int offset) {
    int lineStart = offset;
    while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
    int indentEnd = lineStart;
    while (indentEnd < text.length() && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
    return text.substring(lineStart, indentEnd);
  }

  /**
   * Shifts every line of the blob so its first code line sits at the target
   * indent; the trailing whitespace-only line (the NEXT directive's indent)
   * becomes exactly the target.
   */
  private static String reindentBlob(String blob, String target, CommonCodeStyleSettings.IndentOptions indent) {
    if (blob.indexOf('\n') < 0) return blob;
    String[] lines = blob.split("\n", -1);

    int targetColumns = indentWidth(target, indent.TAB_SIZE);
    int referenceColumns = -1;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        referenceColumns = indentWidth(leadingWhitespace(lines[i]), indent.TAB_SIZE);
        break;
      }
    }
    if (referenceColumns < 0) referenceColumns = targetColumns;
    int delta = targetColumns - referenceColumns;

    StringBuilder result = new StringBuilder(blob.length());
    result.append(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      result.append('\n');
      String line = lines[i];
      boolean trailingIndentLine = i == lines.length - 1 && line.isBlank();
      if (trailingIndentLine) {
        result.append(target);
      }
      else if (!line.isBlank()) {
        String lead = leadingWhitespace(line);
        int columns = Math.max(0, indentWidth(lead, indent.TAB_SIZE) + delta);
        result.append(renderIndent(columns, indent));
        result.append(line, lead.length(), line.length());
      }
    }
    return result.toString();
  }

  private static String leadingWhitespace(String line) {
    int end = 0;
    while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) end++;
    return line.substring(0, end);
  }

  private static int indentWidth(String whitespace, int tabSize) {
    int columns = 0;
    for (int i = 0; i < whitespace.length(); i++) {
      columns = whitespace.charAt(i) == '\t' ? (columns / tabSize + 1) * tabSize : columns + 1;
    }
    return columns;
  }

  private static String renderIndent(int columns, CommonCodeStyleSettings.IndentOptions indent) {
    if (!indent.USE_TAB_CHARACTER) return " ".repeat(columns);
    return "\t".repeat(columns / indent.TAB_SIZE) + " ".repeat(columns % indent.TAB_SIZE);
  }
}
