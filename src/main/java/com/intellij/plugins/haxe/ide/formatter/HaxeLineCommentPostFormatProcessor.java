package com.intellij.plugins.haxe.ide.formatter;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.lang.psi.HaxeFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import org.jetbrains.annotations.NotNull;

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
public class HaxeLineCommentPostFormatProcessor implements PostFormatProcessor {

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
    if (!settings.getCustomSettings(HaxeCodeStyleSettings.class).ADD_LINE_COMMENT_SPACE) return rangeToReformat;
    Document document = source.getViewProvider().getDocument();
    if (document == null) return rangeToReformat;

    List<ASTNode> comments = SyntaxTraverser.astTraverser(source.getNode())
      .filter(node -> node.getElementType() == MSL_COMMENT)
      .toList();
    if (comments.isEmpty()) return rangeToReformat;

    // replace LAST first: earlier offsets stay valid, and range markers,
    // folding and undo outside the touched comments survive the reformat
    int shift = 0;
    for (ASTNode comment : comments.reversed()) {
      TextRange range = comment.getTextRange();
      if (!rangeToReformat.intersects(range)) continue;
      String text = comment.getText();
      String normalized = normalizedLineComment(text);
      if (normalized.equals(text)) continue;
      document.replaceString(range.getStartOffset(), range.getEndOffset(), normalized);
      shift += normalized.length() - text.length();
    }

    if (shift == 0) return rangeToReformat;
    PsiDocumentManager.getInstance(source.getProject()).commitDocument(document);
    int end = Math.min(rangeToReformat.getEndOffset() + shift, document.getTextLength());
    return new TextRange(rangeToReformat.getStartOffset(), Math.max(rangeToReformat.getStartOffset(), end));
  }

  @NotNull
  private static String normalizedLineComment(@NotNull String text) {
    if (!text.startsWith("//")) return text;
    String content = text.substring(2);
    if (keepsShape(content)) {
      return "//" + content.stripTrailing();
    }
    // the tool right-trims line ends afterwards, so a bare "//" stays bare
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
