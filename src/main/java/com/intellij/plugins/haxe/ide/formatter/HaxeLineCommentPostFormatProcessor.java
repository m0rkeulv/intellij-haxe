package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.MSL_COMMENT;

/**
 * Normalizes a line comment's opener the way haxe-formatter does (its
 * printCommentLine): "//text" becomes "// text". Content opening with '/',
 * '*', '-' or whitespace keeps its shape - divider art ("//----", "////"),
 * doc-style "///" and already-spaced text are only right-trimmed. A
 * comment's text is INSIDE its token, out of block formatting's reach -
 * hence a text pass.
 */
public class HaxeLineCommentPostFormatProcessor extends HaxeTextPostFormatProcessor {

  @Override
  protected boolean enabled(@NotNull HaxeCodeStyleSettings settings) {
    return settings.ADD_LINE_COMMENT_SPACE;
  }

  @Override
  protected boolean handles(@NotNull ASTNode node) {
    return node.getElementType() == MSL_COMMENT;
  }

  @Override
  protected @NotNull List<Replacement> replacements(@NotNull List<ASTNode> comments, @NotNull Pass pass) {
    List<Replacement> replacements = new ArrayList<>();
    for (ASTNode comment : comments) {
      if (!pass.editable(comment)) continue;
      String text = comment.getText();
      String normalized = normalizedLineComment(text);
      if (!normalized.equals(text)) {
        replacements.add(new Replacement(comment, normalized));
      }
    }
    return replacements;
  }

  @NotNull
  private static String normalizedLineComment(@NotNull String text) {
    if (!text.startsWith("//")) return text;
    String content = text.substring(2);
    if (keepsShape(content)) {
      return "//" + content.stripTrailing();
    }
    // haxe-formatter right-trims line ends afterwards, so a bare "//" stays bare
    String stripped = content.strip();
    return stripped.isEmpty() ? "//" : "// " + stripped;
  }

  /** Divider art, extra slashes and already-spaced text keep their shape. */
  private static boolean keepsShape(@NotNull String content) {
    if (content.isEmpty()) return false;
    char first = content.charAt(0);
    return first == '/' || first == '*' || first == '-' || Character.isWhitespace(first);
  }
}
