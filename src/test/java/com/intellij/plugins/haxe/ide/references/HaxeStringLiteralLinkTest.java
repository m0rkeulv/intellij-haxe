package com.intellij.plugins.haxe.ide.references;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.ide.highlight.HaxeSyntaxHighlighterColors;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeStringLiteralExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("References: string literal links")
public class HaxeStringLiteralLinkTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/references/";
  }

  private HaxeStringLiteralExpression firstStringLiteral(PsiFile file) {
    HaxeStringLiteralExpression literal = PsiTreeUtil.findChildOfType(file, HaxeStringLiteralExpression.class);
    assertNotNull(literal, "test source must contain a string literal");
    return literal;
  }

  @SuppressWarnings("unchecked")
  private <T extends PsiReference> T singleReferenceOfType(PsiReference[] references, Class<T> type) {
    T found = null;
    for (PsiReference reference : references) {
      if (type.isInstance(reference)) {
        assertNull(found, "expected exactly one " + type.getSimpleName());
        found = (T)reference;
      }
    }
    assertNotNull(found, "expected a " + type.getSimpleName() + " on the literal");
    return found;
  }

  private static boolean hasLinkReference(PsiReference[] references) {
    for (PsiReference reference : references) {
      if (reference instanceof HaxeStringFilePathReference || reference instanceof HaxeStringQnameReference) return true;
    }
    return false;
  }

  @Test
  @DisplayName("project relative path resolves to the file")
  public void testProjectRelativePathResolvesToTheFile() {
    myFixture.addFileToProject("assets/data.txt", "payload");
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static function main() { var p = "assets/data.txt"; } }
      """);

    PsiReference[] references = firstStringLiteral(source).getReferences();
    HaxeStringFilePathReference reference = singleReferenceOfType(references, HaxeStringFilePathReference.class);
    PsiElement resolved = reference.resolve();
    assertInstanceOf(PsiFile.class, resolved, "path must resolve to the file");
    assertEquals("data.txt", ((PsiFile)resolved).getName());
  }

  @Test
  @DisplayName("path relative to the containing file resolves")
  public void testPathRelativeToTheContainingFileResolves() {
    myFixture.addFileToProject("sub/notes.txt", "payload");
    PsiFile source = myFixture.addFileToProject("sub/Code.hx", """
      class Code { static final NOTES = "notes.txt"; }
      """);

    PsiReference[] references = firstStringLiteral(source).getReferences();
    HaxeStringFilePathReference reference = singleReferenceOfType(references, HaxeStringFilePathReference.class);
    PsiElement resolved = reference.resolve();
    assertInstanceOf(PsiFile.class, resolved, "sibling file must resolve relative to the containing file");
    assertEquals("notes.txt", ((PsiFile)resolved).getName());
  }

  @Test
  @DisplayName("fully qualified class name resolves to the class")
  public void testFullyQualifiedClassNameResolvesToTheClass() {
    myFixture.addFileToProject("foo/Bar.hx", """
      package foo;
      class Bar { public function new() {} public function baz():Void {} }
      """);
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static final TYPE = "foo.Bar"; }
      """);

    PsiReference[] references = firstStringLiteral(source).getReferences();
    HaxeStringQnameReference reference = singleReferenceOfType(references, HaxeStringQnameReference.class);
    PsiElement resolved = reference.resolve();
    assertInstanceOf(HaxeClass.class, resolved, "qname must resolve to the class");
  }

  @Test
  @DisplayName("fully qualified member name resolves into the class")
  public void testFullyQualifiedMemberNameResolvesIntoTheClass() {
    myFixture.addFileToProject("foo/Bar.hx", """
      package foo;
      class Bar { public function new() {} public function baz():Void {} }
      """);
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static final MEMBER = "foo.Bar.baz"; }
      """);

    PsiReference[] references = firstStringLiteral(source).getReferences();
    HaxeStringQnameReference reference = singleReferenceOfType(references, HaxeStringQnameReference.class);
    assertNotNull(reference.resolve(), "member qname must resolve");
  }

  @Test
  @DisplayName("unresolvable strings contribute no link")
  public void testUnresolvableStringsContributeNoLink() {
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static final MISSING = "does/not/exist.txt"; }
      """);

    boolean linked = hasLinkReference(firstStringLiteral(source).getReferences());
    assertEquals(false, linked, "an unresolvable path must contribute no reference at all");
  }

  @Test
  @DisplayName("interpolated strings are skipped")
  public void testInterpolatedStringsAreSkipped() {
    myFixture.addFileToProject("assets/data.txt", "payload");
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static function main() { var dir = "assets"; var p = 'assets/$dir.txt'; } }
      """);

    HaxeStringLiteralExpression interpolated = null;
    for (HaxeStringLiteralExpression literal : PsiTreeUtil.findChildrenOfType(source, HaxeStringLiteralExpression.class)) {
      if (!literal.getShortTemplateEntryList().isEmpty()) interpolated = literal;
    }
    assertNotNull(interpolated, "fixture must contain an interpolated literal");
    assertEquals(false, hasLinkReference(interpolated.getReferences()), "interpolation has no constant value to link");
  }

  @Test
  @DisplayName("links are painted with their own configurable attributes")
  public void testLinksArePaintedWithTheirOwnConfigurableAttributes() {
    myFixture.addFileToProject("assets/data.txt", "payload");
    myFixture.addFileToProject("foo/Bar.hx", """
      package foo;
      class Bar {}
      """);
    myFixture.configureByText("Main.hx", """
      class Main { static final P = "assets/data.txt"; static final T = "foo.Bar"; }
      """);

    List<HighlightInfo> infos = myFixture.doHighlighting();
    boolean fileLinkPainted = infos.stream().anyMatch(info -> info.forcedTextAttributesKey == HaxeSyntaxHighlighterColors.STRING_FILE_LINK);
    boolean codeLinkPainted = infos.stream().anyMatch(info -> info.forcedTextAttributesKey == HaxeSyntaxHighlighterColors.STRING_CODE_LINK);
    assertTrue(fileLinkPainted, "resolvable path must carry the String-file-link attributes");
    assertTrue(codeLinkPainted, "resolvable qname must carry the String-code-link attributes");
  }

  @Test
  @DisplayName("plain prose contributes no link")
  public void testPlainProseContributesNoLink() {
    PsiFile source = myFixture.addFileToProject("Main.hx", """
      class Main { static final MESSAGE = "hello there, general text"; }
      """);

    assertEquals(false, hasLinkReference(firstStringLiteral(source).getReferences()), "prose must stay plain");
  }
}
