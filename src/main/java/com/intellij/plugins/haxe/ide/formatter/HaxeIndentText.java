package com.intellij.plugins.haxe.ide.formatter;

/** Line-oriented text helpers shared by the formatter processors. */
final class HaxeIndentText {

  private HaxeIndentText() {
  }

  /** The whitespace prefix of the line containing {@code offset}. */
  static String lineIndentAt(CharSequence text, int offset) {
    int lineStart = lineStartOffset(text, offset);
    int indentEnd = lineStart;
    while (indentEnd < text.length() && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
    return text.subSequence(lineStart, indentEnd).toString();
  }

  static String leadingWhitespace(String line) {
    int end = 0;
    while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) end++;
    return line.substring(0, end);
  }

  /** The offset where {@code offset}'s line begins. */
  static int lineStartOffset(CharSequence text, int offset) {
    int lineStart = offset;
    while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
    return lineStart;
  }

  /** The column at {@code offset} on its line, tabs advancing to the next tab stop. */
  static int columnAt(CharSequence text, int offset, int tabSize) {
    int lineStart = lineStartOffset(text, offset);
    int column = 0;
    for (int i = lineStart; i < offset; i++) {
      column = text.charAt(i) == '\t' ? (column / tabSize + 1) * tabSize : column + 1;
    }
    return column;
  }

  /** The column the whitespace reaches, tabs advancing to the next tab stop. */
  static int indentWidth(String whitespace, int tabSize) {
    int columns = 0;
    for (int i = 0; i < whitespace.length(); i++) {
      columns = whitespace.charAt(i) == '\t' ? (columns / tabSize + 1) * tabSize : columns + 1;
    }
    return columns;
  }
}
