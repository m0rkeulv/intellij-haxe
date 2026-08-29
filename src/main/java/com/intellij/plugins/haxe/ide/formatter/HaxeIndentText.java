package com.intellij.plugins.haxe.ide.formatter;

/** Line-indent text helpers shared by the post-format processors. */
final class HaxeIndentText {

  private HaxeIndentText() {
  }

  /** The whitespace prefix of the line containing {@code offset}. */
  static String lineIndentAt(CharSequence text, int offset) {
    int lineStart = offset;
    while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
    int indentEnd = lineStart;
    while (indentEnd < text.length() && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
    return text.subSequence(lineStart, indentEnd).toString();
  }

  static String leadingWhitespace(String line) {
    int end = 0;
    while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) end++;
    return line.substring(0, end);
  }
}
