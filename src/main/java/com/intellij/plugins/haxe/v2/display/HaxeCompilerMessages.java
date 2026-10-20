package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiler diagnostic as the IDE shows it. The compiler quotes code in
 * backticks ({@code Using `display` here ...}); an annotation's text (the
 * Problems view, the status bar) renders the span in quotes and its tooltip
 * renders it as code.
 */
final class HaxeCompilerMessages {

  // a backtick-quoted span on one line: the code the compiler's message names
  private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");

  private HaxeCompilerMessages() {
  }

  /** The message with its code spans in single quotes. */
  @NotNull
  static String plainText(@NotNull String message) {
    return CODE_SPAN.matcher(message).replaceAll("'$1'");
  }

  /** The message as tooltip HTML, its code spans as {@code <code>}. */
  @NotNull
  static String html(@NotNull String message) {
    StringBuilder html = new StringBuilder();
    Matcher span = CODE_SPAN.matcher(message);
    int copied = 0;
    while (span.find()) {
      html.append(StringUtil.escapeXmlEntities(message.substring(copied, span.start())));
      html.append("<code>").append(StringUtil.escapeXmlEntities(span.group(1))).append("</code>");
      copied = span.end();
    }
    html.append(StringUtil.escapeXmlEntities(message.substring(copied)));
    return XmlStringUtil.wrapInHtml(html);
  }
}
