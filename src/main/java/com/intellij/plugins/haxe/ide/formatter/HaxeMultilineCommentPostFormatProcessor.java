package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.MML_COMMENT;

/**
 * Reindents the interior lines of plain multi-line comments the way
 * haxe-formatter does (its MarkTokenText.printComment): leading whitespace
 * normalizes to the indent character, the shortest common margin is removed,
 * middle lines sit one level deeper than the comment ("*"-railed lines align
 * under the opener), and the closing line returns to the comment's level.
 * A comment's interior is INSIDE its token, out of block formatting's reach -
 * hence a text pass. Doc comments have their own line-structured blocks;
 * single-line comments carry no interior. Disable to restore the IntelliJ
 * convention of leaving comment interiors alone.
 */
public class HaxeMultilineCommentPostFormatProcessor extends HaxeTextPostFormatProcessor {

  // a "*"-railed middle line: whitespace, a star, then a space or nothing
  private static final Pattern STAR_RAIL_LINE = Pattern.compile("^\\s*\\*(\\s|$)");
  // a closing line carrying nothing but stars, or opening with a brace -
  // such lines stay out of the common-margin scan
  private static final Pattern CLOSING_ONLY_LINE = Pattern.compile("^\\s*(\\**$|\\})");
  // a closing line that is only stars (the classic "**/" ending)
  private static final Pattern STARS_ONLY_LINE = Pattern.compile("^\\s*\\*\\**$");

  @Override
  protected boolean enabled(@NotNull HaxeCodeStyleSettings settings) {
    return settings.REINDENT_MULTILINE_COMMENTS;
  }

  @Override
  protected boolean handles(@NotNull ASTNode node) {
    return node.getElementType() == MML_COMMENT && node.textContains('\n');
  }

  @Override
  protected @NotNull List<Replacement> replacements(@NotNull List<ASTNode> comments, @NotNull Pass pass) {
    CommonCodeStyleSettings.IndentOptions options = pass.indentOptions();
    boolean keepFirstColumn = pass.settings().getCommonSettings(HaxeLanguage.INSTANCE).KEEP_FIRST_COLUMN_COMMENT;
    String documentText = pass.text();

    List<Replacement> replacements = new ArrayList<>();
    for (ASTNode comment : comments) {
      if (!pass.editable(comment)) continue;
      int start = comment.getStartOffset();
      // block formatting left this opener pinned at the first column - the
      // comment is intentionally at the margin, so its interior stays put too
      boolean pinnedAtFirstColumn = keepFirstColumn && HaxeIndentText.lineStartOffset(documentText, start) == start;
      if (pinnedAtFirstColumn) continue;
      String text = documentText.substring(start, start + comment.getTextLength());
      String baseIndent = HaxeIndentText.lineIndentAt(documentText, start);
      String reindented = reindent(text, baseIndent, options);
      if (!reindented.equals(text)) {
        replacements.add(new Replacement(comment, reindented));
      }
    }
    return replacements;
  }

  private static String reindent(String text, String baseIndent, CommonCodeStyleSettings.IndentOptions options) {
    if (!text.startsWith("/*") || !text.endsWith("*/") || text.length() < 4) return text;
    String content = text.substring(2, text.length() - 2);
    // every line, trailing empty ones kept
    String[] lines = content.split("\n", -1);
    if (lines.length < 2) return text;
    boolean starRailed = starRailed(lines);

    for (int i = 0; i < lines.length; i++) {
      lines[i] = convertLeadingIndent(lines[i], options);
    }
    removeCommonMargin(lines);

    String unit = indentUnit(options);
    int last = lines.length - 1;
    StringBuilder out = new StringBuilder("/*").append(lines[0]);
    for (int i = 1; i <= last; i++) {
      String formatted = i == last
                         ? formatClosingLine(lines[i], baseIndent, unit)
                         : formatMiddleLine(lines[i], baseIndent, unit, starRailed);
      out.append('\n').append(formatted);
    }
    return out.append("*/").toString();
  }

  /** Every middle line rides a "*" rail - the style whose stars align under the opener. */
  private static boolean starRailed(String[] lines) {
    if (lines.length < 3) return false;
    for (int i = 1; i < lines.length - 1; i++) {
      if (!STAR_RAIL_LINE.matcher(lines[i]).find()) return false;
    }
    return true;
  }

  /**
   * A middle line, indent included: one level in from the comment, or - on
   * a "*" rail - the space that puts the star under the opener's; an empty
   * line carries no indent.
   */
  private static String formatMiddleLine(String line, String baseIndent, String unit, boolean starRailed) {
    String text = starRailed ? " " + line : line;
    if (line.isEmpty()) return text;
    String lineIndent = starRailed ? baseIndent : baseIndent + unit;
    return lineIndent + text;
  }

  /**
   * The closing line, indent included: railed text and braces keep the base
   * indent, plain text sits one level in, and a "*"-less closer gets the
   * space that separates it from the trailing star pair.
   */
  private static String formatClosingLine(String line, String baseIndent, String unit) {
    String body = line.stripLeading();
    if (body.startsWith("}")) return baseIndent + body.stripTrailing();
    boolean plainText = !body.isEmpty() && body.charAt(0) != '*';
    String lineIndent = plainText ? baseIndent + unit : baseIndent;
    String text = railedText(body) ? " " + line : line;
    text = text.stripTrailing();
    if (!text.endsWith("*")) {
      text = text + " ";
    }
    return lineIndent + text;
  }

  /** A star followed by text - a closer like "* done", not "**" or a lone star. */
  private static boolean railedText(String body) {
    if (!body.startsWith("*")) return false;
    String afterStar = body.substring(1).stripLeading();
    return !afterStar.isEmpty() && afterStar.charAt(0) != '*';
  }

  /**
   * Strips the shortest non-empty leading-whitespace margin found among the
   * interior lines from every line; a "margin + space + star" rail loses the
   * margin and the space but keeps its star.
   */
  private static void removeCommonMargin(String[] lines) {
    int endIndex = lines.length - 1;
    if (!CLOSING_ONLY_LINE.matcher(lines[lines.length - 1]).find()) {
      endIndex = lines.length;
    }
    String margin = null;
    for (int i = 1; i < endIndex; i++) {
      String lead = HaxeIndentText.leadingWhitespace(lines[i], 0);
      if (lead.isEmpty()) continue;
      if (margin == null || margin.length() > lead.length()) {
        margin = lead;
      }
    }
    if (margin != null) {
      String starMargin = margin + " *";
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (line.startsWith(starMargin)) {
          line = line.substring(starMargin.length() - 1);
        }
        if (line.startsWith(margin)) {
          line = line.substring(margin.length());
        }
        lines[i] = line;
      }
    }
    String last = lines[lines.length - 1];
    if (STARS_ONLY_LINE.matcher(last).find()) {
      lines[lines.length - 1] = last.stripLeading();
    }
  }

  /**
   * One indent level of comment interior. Inside a comment one tab counts
   * as one level (never as TAB_SIZE columns), so a tab-indented level is a
   * single tab; {@link #convertLeadingIndent} converts by the same rule.
   */
  private static String indentUnit(CommonCodeStyleSettings.IndentOptions options) {
    return options.USE_TAB_CHARACTER ? "\t" : " ".repeat(options.INDENT_SIZE);
  }

  /**
   * Normalizes a line's leading whitespace toward the indent character by
   * the one-tab-per-level rule of {@link #indentUnit}: tab-indenting
   * replaces each run of TAB_SIZE spaces with one tab, space-indenting
   * replaces each tab with one indent unit (INDENT_SIZE spaces).
   */
  private static String convertLeadingIndent(String line, CommonCodeStyleSettings.IndentOptions options) {
    String lead = HaxeIndentText.leadingWhitespace(line, 0);
    if (lead.isEmpty()) return line;
    String spaceRun = " ".repeat(options.TAB_SIZE);
    String converted = options.USE_TAB_CHARACTER
                       ? lead.replace(spaceRun, "\t")
                       : lead.replace("\t", " ".repeat(options.INDENT_SIZE));
    return converted + line.substring(lead.length());
  }
}
