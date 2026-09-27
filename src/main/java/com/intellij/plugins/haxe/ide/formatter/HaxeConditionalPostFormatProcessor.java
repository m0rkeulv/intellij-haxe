package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.formatting.IndentInfo;
import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.containers.JBIterable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.PPBODY;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.WHITESPACES;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.*;

/**
 * Aligns the INACTIVE branches of #if/#elseif/#else regions after a reformat.
 * Branches that parse cleanly are block-formatted like active code
 * (FORMAT_INACTIVE_BRANCHES owns those); this pass serves only the
 * unparsable token-soup blobs, whose lines it aligns as a group - the
 * branch's own relative nesting preserved - to the region's nearest
 * preceding directive. Single-line (inline expression) regions are
 * untouched.
 * <p>
 * A region NESTED inside an inactive branch splits that branch into
 * fragments (one PPBODY per stretch between directives) that the parser
 * sees one at a time - a '{' in one fragment, its '}' in another - with
 * the inner directives as leaves of the enclosing PSI level. The engine
 * indents every fragment and inner directive at that level, so the written
 * depth is gone before this pass runs. Such a branch is aligned as ONE
 * group from its opening directive: each line's depth follows the braces
 * the fragments before it opened, the clean-parsing fragments included
 * (the engine formatted them at the level of the enclosing code).
 *
 * TODO: the depth follows braces only; a nested region inside a brace-less
 *       body (a switch case, an unbraced if/for body) sits one level short.
 */
public class HaxeConditionalPostFormatProcessor extends HaxeTextPostFormatProcessor {

  private static final TokenSet PP_LEAVES = TokenSet.create(PPIF, PPELSEIF, PPELSE, PPEND, PPBODY);

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
    return new RegionWalk(ppLeaves, pass).run();
  }

  /**
   * Block formatting owns branches with parsed structure; alignment only
   * serves the token-soup blobs the formatter preserves verbatim.
   */
  private static boolean blockFormatted(@NotNull ASTNode leaf, @NotNull HaxeCodeStyleSettings settings) {
    return leaf.getPsi() instanceof HaxeInactiveBody body && !HaxeInactiveBranches.preservedVerbatim(body, settings);
  }

  /** '{' minus '}' over the blob's tokens; strings and comments are tokens of their own, so their braces never count. */
  private static int braceBalance(@NotNull ASTNode blob) {
    int balance = 0;
    for (ASTNode token : tokens(blob)) {
      if (token.getElementType() == PLCURLY) balance++;
      else if (token.getElementType() == PRCURLY) balance--;
    }
    return balance;
  }

  /** The blob's first token is a '}' starting an interior line: that line closes a brace and sits one level out. */
  private static boolean opensWithClosingBrace(@NotNull ASTNode blob) {
    ASTNode first = tokens(blob).filter(token -> !WHITESPACES.contains(token.getElementType())).first();
    if (first == null || first.getElementType() != PRCURLY) return false;
    int offsetInBlob = first.getStartOffset() - blob.getStartOffset();
    return blob.getText().lastIndexOf('\n', offsetInBlob) >= 0;
  }

  /** The blob's lazily parsed leaves in file order (its own node excluded). */
  private static JBIterable<ASTNode> tokens(@NotNull ASTNode blob) {
    return SyntaxTraverser.astTraverser(blob)
      .filter(node -> node != blob && node.getFirstChildNode() == null)
      .traverse();
  }

  /**
   * Shifts every line of the blob so its first code line sits at the
   * first-line indent; the trailing whitespace-only line (the NEXT
   * directive's indent) becomes exactly the trailing indent.
   */
  private static String reindentBlob(String blob, String firstLineIndent, String trailingIndent, CommonCodeStyleSettings.IndentOptions options) {
    if (blob.indexOf('\n') < 0) return blob;
    // every line, trailing empty ones kept
    String[] lines = blob.split("\n", -1);
    int targetColumns = HaxeIndentText.indentWidth(firstLineIndent, options.TAB_SIZE);
    int referenceColumns = firstInteriorIndentColumns(lines, options.TAB_SIZE);
    int delta = referenceColumns < 0 ? 0 : targetColumns - referenceColumns;

    StringBuilder result = new StringBuilder(blob.length());
    result.append(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      result.append('\n');
      String line = lines[i];
      boolean trailingIndentLine = i == lines.length - 1 && line.isBlank();
      if (trailingIndentLine) {
        result.append(trailingIndent);
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

  /**
   * The walk over the directive and branch leaves in file order, editing a
   * working copy of the text as it goes: a blob's rewrite fixes the line
   * indent of the directive AFTER it before that directive is read as the
   * next blob's alignment target.
   */
  private static final class RegionWalk {
    private final List<ASTNode> leaves;
    private final Pass pass;
    private final CommonCodeStyleSettings.IndentOptions options;
    private final StringBuilder working;
    private final List<Replacement> replacements = new ArrayList<>();
    private int shift;
    /** Open #if regions at the current leaf. */
    private int depth;
    /** The line indent of the nearest preceding directive of the enclosing code; null before the first. */
    @Nullable private String targetIndent;
    /** The grouped branch being walked; null outside one. */
    @Nullable private Group group;

    RegionWalk(List<ASTNode> leaves, Pass pass) {
      this.leaves = leaves;
      this.pass = pass;
      options = pass.indentOptions();
      working = new StringBuilder(pass.text());
    }

    List<Replacement> run() {
      for (int i = 0; i < leaves.size(); i++) {
        if (leaves.get(i).getElementType() == PPBODY) branch(i);
        else directive(i);
      }
      return replacements;
    }

    private void directive(int index) {
      ASTNode leaf = leaves.get(index);
      IElementType type = leaf.getElementType();
      if (type == PPIF) depth++;
      boolean inner = group != null && depth > group.depth;
      if (inner) {
        // its line indent is the trailing line of the fragment before it
        if (type == PPEND) depth--;
        return;
      }
      group = null;
      if (type == PPEND) {
        depth = Math.max(0, depth - 1);
        return;
      }
      targetIndent = HaxeIndentText.lineIndentAt(working, leaf.getStartOffset() + shift);
      if (opensGroupedBranch(index)) group = new Group(depth, HaxeIndentText.indentWidth(targetIndent, options.TAB_SIZE));
    }

    /** The directive's branch is inactive and holds a nested region: a blob follows it, and a #if follows that blob. */
    private boolean opensGroupedBranch(int index) {
      if (index + 2 >= leaves.size()) return false;
      return leaves.get(index + 1).getElementType() == PPBODY && leaves.get(index + 2).getElementType() == PPIF;
    }

    private void branch(int index) {
      ASTNode leaf = leaves.get(index);
      if (group == null) {
        boolean alignable = targetIndent != null && !blockFormatted(leaf, pass.haxeSettings());
        if (alignable) reindent(leaf, targetIndent, targetIndent);
        return;
      }
      int firstLineDepth = Math.max(0, group.braceDepth - (opensWithClosingBrace(leaf) ? 1 : 0));
      group.braceDepth = Math.max(0, group.braceDepth + braceBalance(leaf));
      // the directive closing the group belongs to the enclosing code and
      // keeps the opener's level whatever the fragments left open
      int trailingDepth = closesGroupAfter(index) ? 0 : group.braceDepth;
      reindent(leaf, groupIndent(firstLineDepth), groupIndent(trailingDepth));
    }

    /** The directive after the blob ends the grouped branch: not a nested #if, and at the group's own depth. */
    private boolean closesGroupAfter(int index) {
      if (index + 1 >= leaves.size()) return true;
      return leaves.get(index + 1).getElementType() != PPIF && depth == group.depth;
    }

    /** The group opener's column plus {@code levels} indent steps, rendered per the indent options. */
    private String groupIndent(int levels) {
      return renderIndent(group.baseColumns + levels * options.INDENT_SIZE, options);
    }

    private void reindent(ASTNode leaf, String firstLineIndent, String trailingIndent) {
      if (!pass.covers(leaf)) return;
      int start = leaf.getStartOffset() + shift;
      String blob = working.substring(start, start + leaf.getTextLength());
      String reindented = reindentBlob(blob, firstLineIndent, trailingIndent, options);
      working.replace(start, start + blob.length(), reindented);
      shift += reindented.length() - blob.length();
      if (!reindented.equals(blob)) {
        replacements.add(new Replacement(leaf, reindented));
      }
    }
  }

  /** An inactive branch holding nested regions: the depth its directive opened at, its line's column, and the braces its fragments have opened so far. */
  private static final class Group {
    final int depth;
    final int baseColumns;
    int braceDepth;

    Group(int depth, int baseColumns) {
      this.depth = depth;
      this.baseColumns = baseColumns;
    }
  }
}
