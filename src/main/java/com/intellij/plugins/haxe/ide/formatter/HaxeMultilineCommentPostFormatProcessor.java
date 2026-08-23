package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
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
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

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
public class HaxeMultilineCommentPostFormatProcessor implements PostFormatProcessor {

  // a "*"-railed middle line: whitespace, a star, then a space or nothing
  private static final Pattern STAR_RAIL_LINE = Pattern.compile("^\\s*\\*(\\s|$)");
  // a closing line carrying nothing but stars, or opening with a brace -
  // such lines stay out of the common-margin scan
  private static final Pattern CLOSING_ONLY_LINE = Pattern.compile("^\\s*(\\**$|\\})");
  // a closing line that is only stars (the classic "**/" ending)
  private static final Pattern STARS_ONLY_LINE = Pattern.compile("^\\s*\\*\\**$");
  // a closing line with railed text: whitespace, star, text
  private static final Pattern CLOSING_STAR_TEXT = Pattern.compile("^\\s*\\*\\s*[^\\s*]");
  // a closing line opening with a brace
  private static final Pattern CLOSING_BRACE = Pattern.compile("^\\s*\\}");
  // a closing line opening with plain (unrailed) text
  private static final Pattern CLOSING_PLAIN_TEXT = Pattern.compile("^\\s*[^*\\s]");

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
    if (!haxeSettings.REINDENT_MULTILINE_COMMENTS) return rangeToReformat;
    Document document = source.getViewProvider().getDocument();
    if (document == null) return rangeToReformat;

    List<ASTNode> comments = SyntaxTraverser.astTraverser(source.getNode())
      .filter(node -> node.getElementType() == MML_COMMENT && node.textContains('\n'))
      .toList();
    if (comments.isEmpty()) return rangeToReformat;

    CommonCodeStyleSettings.IndentOptions indent = settings.getIndentOptions(HaxeFileType.INSTANCE);
    boolean keepFirstColumn = settings.getCommonSettings(HaxeLanguage.INSTANCE).KEEP_FIRST_COLUMN_COMMENT;
    String original = document.getText();
    StringBuilder working = new StringBuilder(original);

    int shift = 0;
    for (ASTNode comment : comments) {
      boolean inRange = rangeToReformat.intersects(comment.getStartOffset(), comment.getStartOffset() + comment.getTextLength());
      if (!inRange) continue;
      if (inPreservedInactiveBranch(comment, haxeSettings)) continue;
      int start = comment.getStartOffset() + shift;
      // block formatting left this opener pinned at the first column - the
      // comment is intentionally at the margin, so its interior stays put too
      boolean pinnedAtFirstColumn = keepFirstColumn && (start == 0 || working.charAt(start - 1) == '\n');
      if (pinnedAtFirstColumn) continue;
      String text = working.substring(start, start + comment.getTextLength());
      String baseIndent = HaxeIndentText.lineIndentAt(working, start);
      String reindented = reindent(text, baseIndent, indent);
      working.replace(start, start + text.length(), reindented);
      shift += reindented.length() - text.length();
    }

    if (shift == 0 && working.toString().equals(original)) return rangeToReformat;
    document.replaceString(0, original.length(), working);
    PsiDocumentManager.getInstance(source.getProject()).commitDocument(document);
    int end = Math.min(rangeToReformat.getEndOffset() + shift, working.length());
    return new TextRange(rangeToReformat.getStartOffset(), Math.max(rangeToReformat.getStartOffset(), end));
  }

  /**
   * Comments inside an inactive branch reindent only when the branch itself
   * is block-formatted; preserved-verbatim branches stay byte-identical.
   */
  private static boolean inPreservedInactiveBranch(ASTNode comment, HaxeCodeStyleSettings settings) {
    HaxeInactiveBody body = PsiTreeUtil.getParentOfType(comment.getPsi(), HaxeInactiveBody.class);
    if (body == null) return false;
    return !settings.FORMAT_INACTIVE_BRANCHES || !body.hasCleanParse();
  }

  private static String reindent(String text, String baseIndent, CommonCodeStyleSettings.IndentOptions options) {
    if (!text.startsWith("/*") || !text.endsWith("*/") || text.length() < 4) return text;
    String content = text.substring(2, text.length() - 2);
    String[] lines = content.split("\n", -1);
    if (lines.length < 2) return text;

    boolean startsWithStar = lines.length >= 3;
    for (int i = 1; i < lines.length - 1 && startsWithStar; i++) {
      startsWithStar = STAR_RAIL_LINE.matcher(lines[i]).find();
    }

    for (int i = 0; i < lines.length; i++) {
      lines[i] = convertLeadingIndent(lines[i], options);
    }
    removeCommonMargin(lines);

    String unit = options.USE_TAB_CHARACTER ? "\t" : " ".repeat(options.INDENT_SIZE);
    StringBuilder out = new StringBuilder("/*").append(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      out.append('\n');
      String line = lines[i];
      boolean lastLine = i == lines.length - 1;
      String lineIndent = lastLine || startsWithStar ? baseIndent : baseIndent + unit;
      if (!lastLine && line.isEmpty()) {
        lineIndent = "";
      }
      if (!lastLine && startsWithStar) {
        line = " " + line;
      }
      if (lastLine) {
        if (CLOSING_STAR_TEXT.matcher(line).find()) {
          line = " " + line;
        }
        if (CLOSING_BRACE.matcher(line).find()) {
          line = line.trim();
        }
        else {
          if (CLOSING_PLAIN_TEXT.matcher(line).find()) {
            lineIndent = baseIndent + unit;
          }
          line = line.stripTrailing();
          if (!line.endsWith("*")) {
            line = line + " ";
          }
        }
        if (line.isBlank()) {
          line = " ";
        }
      }
      out.append(lineIndent).append(line);
    }
    return out.append("*/").toString();
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
      String lead = HaxeIndentText.leadingWhitespace(lines[i]);
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
   * Normalizes a line's leading whitespace toward the indent character:
   * tab-indenting replaces each run of TAB_SIZE spaces with one tab,
   * space-indenting replaces each tab with one indent unit.
   */
  private static String convertLeadingIndent(String line, CommonCodeStyleSettings.IndentOptions options) {
    String lead = HaxeIndentText.leadingWhitespace(line);
    if (lead.isEmpty()) return line;
    String spaceRun = " ".repeat(options.TAB_SIZE);
    String converted = options.USE_TAB_CHARACTER
                       ? lead.replace(spaceRun, "\t")
                       : lead.replace("\t", " ".repeat(options.INDENT_SIZE));
    return converted + line.substring(lead.length());
  }
}
