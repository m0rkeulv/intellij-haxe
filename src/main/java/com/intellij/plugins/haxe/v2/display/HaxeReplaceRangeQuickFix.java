package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces the span a compiler diagnostic marked as removable with the
 * compiler's replacement text — empty replacement means plain removal
 * (haxe 4 sends no replacement; haxe 5 may supply {@code newCode}).
 * Diagnostics are a background snapshot, so the fix re-validates that the
 * document still carries the captured text at the captured offsets and
 * quietly does nothing when the code moved. A line left blank by a removal
 * is removed whole.
 */
final class HaxeReplaceRangeQuickFix implements IntentionAction {
  private final String text;
  private final TextRange range;
  private final String expectedText;
  private final String replacement;

  HaxeReplaceRangeQuickFix(@NotNull String text, @NotNull TextRange range,
                           @NotNull String expectedText, @NotNull String replacement) {
    this.text = text;
    this.range = range;
    this.expectedText = expectedText;
    this.replacement = replacement;
  }

  @Override
  public @NotNull String getText() {
    return text;
  }

  @Override
  public @NotNull String getFamilyName() {
    return HaxeBundle.message("haxe.diagnostics.fix.family");
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    return editor != null && rangeStillMatches(editor.getDocument());
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    if (editor == null) return;
    Document document = editor.getDocument();
    if (!rangeStillMatches(document)) return;

    if (replacement.isEmpty()) {
      TextRange toDelete = widenToBlankedLines(document, range);
      document.deleteString(toDelete.getStartOffset(), toDelete.getEndOffset());
    }
    else {
      document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
    }
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }

  private boolean rangeStillMatches(@NotNull Document document) {
    if (range.getEndOffset() > document.getTextLength()) return false;
    return expectedText.equals(document.getText(range));
  }

  /** When the deletion leaves only whitespace on its line(s), take the whole lines including the break. */
  @NotNull
  private static TextRange widenToBlankedLines(@NotNull Document document, @NotNull TextRange range) {
    int startLine = document.getLineNumber(range.getStartOffset());
    int endLine = document.getLineNumber(range.getEndOffset());
    int lineStart = document.getLineStartOffset(startLine);
    int lineEnd = document.getLineEndOffset(endLine);

    String before = document.getText(new TextRange(lineStart, range.getStartOffset()));
    String after = document.getText(new TextRange(range.getEndOffset(), lineEnd));
    if (!before.isBlank() || !after.isBlank()) return range;

    int withSeparator = endLine + 1 < document.getLineCount()
                        ? document.getLineStartOffset(endLine + 1)
                        : lineEnd;
    return new TextRange(lineStart, withSeparator);
  }
}
