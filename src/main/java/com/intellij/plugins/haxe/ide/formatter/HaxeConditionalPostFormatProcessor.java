package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.formatting.IndentInfo;
import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
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
public class HaxeConditionalPostFormatProcessor extends HaxeTextPostFormatProcessor {

  private static final TokenSet PP_DIRECTIVES = TokenSet.create(PPIF, PPELSEIF, PPELSE);
  private static final TokenSet PP_LEAVES = TokenSet.create(PPIF, PPELSEIF, PPELSE, PPBODY);

  @Override
  protected boolean enabled(@NotNull HaxeCodeStyleSettings settings) {
    return settings.ALIGN_INACTIVE_CONDITIONAL_BRANCHES;
  }

  @Override
  protected boolean handles(@NotNull ASTNode node) {
    return PP_LEAVES.contains(node.getElementType());
  }

  @Override
  protected @NotNull List<Replacement> replacements(@NotNull List<ASTNode> ppLeaves, @NotNull Pass pass) {
    CommonCodeStyleSettings.IndentOptions options = pass.indentOptions();
    StringBuilder working = new StringBuilder(pass.text());

    // leaves are visited in file order, so a blob's rewrite fixes the line
    // indent of the directive AFTER it before that directive is read as the
    // next blob's alignment target
    int shift = 0;
    String targetIndent = null;
    List<Replacement> replacements = new ArrayList<>();
    for (ASTNode leaf : ppLeaves) {
      int start = leaf.getStartOffset() + shift;
      if (PP_DIRECTIVES.contains(leaf.getElementType())) {
        targetIndent = HaxeIndentText.lineIndentAt(working, start);
        continue;
      }
      boolean alignable = targetIndent != null && pass.covers(leaf) && !blockFormatted(leaf, pass.haxeSettings());
      if (!alignable) continue;
      String blob = working.substring(start, start + leaf.getTextLength());
      String reindented = reindentBlob(blob, targetIndent, options);
      working.replace(start, start + blob.length(), reindented);
      shift += reindented.length() - blob.length();
      if (!reindented.equals(blob)) {
        replacements.add(new Replacement(leaf, reindented));
      }
    }
    return replacements;
  }

  /**
   * Block formatting owns branches with parsed structure; alignment only
   * serves the token-soup blobs the formatter preserves verbatim.
   */
  private static boolean blockFormatted(@NotNull ASTNode leaf, @NotNull HaxeCodeStyleSettings settings) {
    return leaf.getPsi() instanceof HaxeInactiveBody body && !HaxeInactiveBranches.preservedVerbatim(body, settings);
  }

  /**
   * Shifts every line of the blob so its first code line sits at the target
   * indent; the trailing whitespace-only line (the NEXT directive's indent)
   * becomes exactly the target.
   */
  private static String reindentBlob(String blob, String targetIndent, CommonCodeStyleSettings.IndentOptions options) {
    if (blob.indexOf('\n') < 0) return blob;
    // every line, trailing empty ones kept
    String[] lines = blob.split("\n", -1);
    int targetColumns = HaxeIndentText.indentWidth(targetIndent, options.TAB_SIZE);
    int referenceColumns = firstInteriorIndentColumns(lines, options.TAB_SIZE);
    int delta = referenceColumns < 0 ? 0 : targetColumns - referenceColumns;

    StringBuilder result = new StringBuilder(blob.length());
    result.append(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      result.append('\n');
      String line = lines[i];
      boolean trailingIndentLine = i == lines.length - 1 && line.isBlank();
      if (trailingIndentLine) {
        result.append(targetIndent);
      }
      else if (!line.isBlank()) {
        String lead = HaxeIndentText.leadingWhitespace(line, 0);
        int columns = Math.max(0, HaxeIndentText.indentWidth(lead, options.TAB_SIZE) + delta);
        result.append(renderIndent(columns, options));
        result.append(line, lead.length(), line.length());
      }
    }
    return result.toString();
  }

  /** The indent column of the first non-blank line after the opening one; -1 when there is none. */
  private static int firstInteriorIndentColumns(String[] lines, int tabSize) {
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) continue;
      return HaxeIndentText.indentWidth(HaxeIndentText.leadingWhitespace(lines[i], 0), tabSize);
    }
    return -1;
  }

  /** The whitespace reaching the column: tabs by TAB_SIZE plus a space remainder, or spaces only. */
  private static String renderIndent(int columns, CommonCodeStyleSettings.IndentOptions options) {
    return new IndentInfo(0, columns, 0).generateNewWhiteSpace(options);
  }
}
