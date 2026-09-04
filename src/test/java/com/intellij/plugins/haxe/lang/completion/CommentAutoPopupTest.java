package com.intellij.plugins.haxe.lang.completion;

import com.intellij.plugins.haxe.ide.completion.HaxeCommentCompletionConfidence;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Completion: comment auto-popup")
public class CommentAutoPopupTest extends HaxeCompletionTestBase {
  public CommentAutoPopupTest() {
    super("completion");
  }

  /** (position, source with the caret right after the typed character, auto-popup skipped). */
  static final List<Arguments> POSITIONS = List.of(
    arguments("line comment", "// prose.<caret>\nclass A {}", true),
    arguments("doc comment", "/**\n  prose.<caret>\n**/\nclass A {}", true),
    arguments("code", "class A { function f(a:A) { a.<caret> } }", false),
    // comment-shaped for the platform, but still code
    arguments("inactive branch", "class A { function f(a:A) {\n#if never\na.<caret>\n#end\n} }", false));

  @ParameterizedTest(name = "{0}")
  @FieldSource("POSITIONS")
  public void testSkipsAutoPopupOnlyInComments(String position, String source, boolean skipped) {
    PsiFile file = configureFileByText("A.hx", source);
    int offset = myFixture.getCaretOffset();
    PsiElement context = file.findElementAt(offset - 1);

    ThreeState result = new HaxeCommentCompletionConfidence().shouldSkipAutopopup(myFixture.getEditor(), context, file, offset);

    assertEquals(skipped ? ThreeState.YES : ThreeState.UNSURE, result);
  }
}
