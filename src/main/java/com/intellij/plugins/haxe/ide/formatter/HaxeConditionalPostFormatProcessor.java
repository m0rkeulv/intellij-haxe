package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.HaxeFile;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
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

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.PPBODY;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * Aligns the INACTIVE branches of #if/#elseif/#else regions after a reformat.
 * Branches that parse cleanly are block-formatted like active code
 * (FORMAT_INACTIVE_BRANCHES owns those); this pass serves only the
 * unparsable token-soup blobs, whose lines it aligns as a group - the
 * branch's own relative nesting preserved - to the region's nearest
 * preceding directive. Single-line (inline expression) regions are
 * untouched.
 *
 * TODO: a #if NESTED inside an inactive branch splits the branch into
 *       fragments that cannot parse alone (a '{' in one, its '}' in
 *       another); the fragments go verbatim while the inner directives
 *       indent at their PSI level (the class body), and the following
 *       fragments then align to that wrong depth. Needs region-level
 *       grouping from the inactive-CC redesign to keep the written depth.
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
    HaxeCodeStyleSettings haxeSettings = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    if (!haxeSettings.ALIGN_INACTIVE_CONDITIONAL_BRANCHES) return rangeToReformat;
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
    List<Replacement> replacements = new ArrayList<>();
    for (ASTNode leaf : ppLeaves) {
      IElementType type = leaf.getElementType();
      int start = leaf.getStartOffset() + shift;
      if (PP_DIRECTIVES.contains(type)) {
        target = HaxeIndentText.lineIndentAt(working, start);
        continue;
      }
      boolean inRange = rangeToReformat.intersects(leaf.getStartOffset(), leaf.getStartOffset() + leaf.getTextLength());
      if (target == null || !inRange) continue;
      // block formatting owns branches with parsed structure; alignment only
      // serves the token-soup blobs the formatter preserves verbatim
      boolean blockFormatted = leaf.getPsi() instanceof HaxeInactiveBody body
                               && !HaxeInactiveBranches.preservedVerbatim(body, haxeSettings);
      if (blockFormatted) continue;
      String blob = working.substring(start, start + leaf.getTextLength());
      String reindented = reindentBlob(blob, target, indent);
      working.replace(start, start + blob.length(), reindented);
      shift += reindented.length() - blob.length();
      if (!reindented.equals(blob)) {
        replacements.add(new Replacement(leaf.getStartOffset(), leaf.getTextLength(), reindented));
      }
    }

    if (replacements.isEmpty()) return rangeToReformat;
    // applied LAST first so the original-coordinate offsets stay valid; the
    // localized edits keep range markers/folding/undo outside the blobs alive
    for (Replacement replacement : replacements.reversed()) {
      document.replaceString(replacement.start, replacement.start + replacement.length, replacement.text);
    }
    PsiDocumentManager.getInstance(source.getProject()).commitDocument(document);
    int end = Math.min(rangeToReformat.getEndOffset() + shift, document.getTextLength());
    return new TextRange(rangeToReformat.getStartOffset(), Math.max(rangeToReformat.getStartOffset(), end));
  }

  /**
   * Shifts every line of the blob so its first code line sits at the target
   * indent; the trailing whitespace-only line (the NEXT directive's indent)
   * becomes exactly the target.
   */
  private static String reindentBlob(String blob, String target, CommonCodeStyleSettings.IndentOptions indent) {
    if (blob.indexOf('\n') < 0) return blob;
    // every line, trailing empty ones kept
    String[] lines = blob.split("\n", -1);

    int targetColumns = HaxeIndentText.indentWidth(target, indent.TAB_SIZE);
    int referenceColumns = -1;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        referenceColumns = HaxeIndentText.indentWidth(HaxeIndentText.leadingWhitespace(lines[i]), indent.TAB_SIZE);
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
        String lead = HaxeIndentText.leadingWhitespace(line);
        int columns = Math.max(0, HaxeIndentText.indentWidth(lead, indent.TAB_SIZE) + delta);
        result.append(renderIndent(columns, indent));
        result.append(line, lead.length(), line.length());
      }
    }
    return result.toString();
  }

  private static String renderIndent(int columns, CommonCodeStyleSettings.IndentOptions indent) {
    if (!indent.USE_TAB_CHARACTER) return " ".repeat(columns);
    return "\t".repeat(columns / indent.TAB_SIZE) + " ".repeat(columns % indent.TAB_SIZE);
  }

  /** A pending document edit in ORIGINAL (pre-edit) coordinates. */
  private record Replacement(int start, int length, String text) {
  }
}
