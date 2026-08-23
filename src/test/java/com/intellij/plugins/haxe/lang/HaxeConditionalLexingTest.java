package com.intellij.plugins.haxe.lang;

import com.intellij.lexer.Lexer;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.lang.lexer.HaxeLexer;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets;
import com.intellij.plugins.haxe.lang.psi.HaxeLocalVarDeclarationList;
import com.intellij.plugins.haxe.lang.psi.HaxeMethodDeclaration;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An inactive conditional branch lexes as ONE PPBODY blob per region between
 * directives (the flex lexer fully lexes dead code and remaps each token;
 * HaxeLexer merges the contiguous run) - the unit inactive-branch handling
 * operates on.
 */
@DisplayName("Lexing: inactive conditional branches")
public class HaxeConditionalLexingTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/parsing/";
  }

  private List<String> ppBodyTokens(String source) {
    Lexer lexer = new HaxeLexer(getProject());
    lexer.start(source);
    List<String> bodies = new ArrayList<>();
    while (lexer.getTokenType() != null) {
      if (lexer.getTokenType() == HaxeTokenTypeSets.PPBODY) {
        bodies.add(lexer.getTokenText());
      }
      lexer.advance();
    }
    return bodies;
  }

  @Test
  @DisplayName("dead branch is one blob token")
  public void testDeadBranchIsOneBlobToken() {
    List<String> bodies = ppBodyTokens("""
      class Foo {
      \t#if never
      \tfunction dead():Void {
      \t\ttrace("gone");
      \t}
      \t#end
      \tfunction live():Void {}
      }""");

    assertEquals(1, bodies.size(), "the whole branch must merge into one PPBODY: " + bodies);
    assertTrue(bodies.get(0).contains("function dead"), "the blob carries the branch's full text");
    assertTrue(bodies.get(0).contains("trace"), "nested content stays inside the blob");
  }

  @Test
  @DisplayName("each region between directives is its own blob")
  public void testEachRegionBetweenDirectivesIsItsOwnBlob() {
    List<String> bodies = ppBodyTokens("""
      class Foo {
      \t#if never
      \tvar a:Int;
      \t#elseif never_either
      \tvar b:Int;
      \t#end
      }""");

    assertEquals(2, bodies.size(), "one blob per inactive region: " + bodies);
    assertTrue(bodies.get(0).contains("var a"));
    assertTrue(bodies.get(1).contains("var b"));
  }

  @Test
  @DisplayName("dead branch is one comment leaf in the tree")
  public void testDeadBranchIsOneCommentLeafInTheTree() {
    PsiFile file = myFixture.configureByText("Foo.hx", """
      class Foo {
      \t#if never
      \tfunction dead():Void {}
      \t#end
      }""");

    List<PsiComment> ppBodies = PsiTreeUtil.findChildrenOfType(file, PsiComment.class).stream()
      .filter(comment -> comment.getTokenType() == HaxeTokenTypeSets.PPBODY)
      .toList();
    assertEquals(1, ppBodies.size(), "the parser must see the merged blob as one comment leaf");
    assertTrue(ppBodies.get(0).getText().contains("function dead"));
  }

  private HaxeInactiveBody inactiveBody(String source) {
    PsiFile file = myFixture.configureByText("Foo.hx", source);
    HaxeInactiveBody body = PsiTreeUtil.findChildOfType(file, HaxeInactiveBody.class);
    assertNotNull(body);
    return body;
  }

  @Test
  @DisplayName("class level branch parses as members")
  public void testClassLevelBranchParsesAsMembers() {
    HaxeInactiveBody body = inactiveBody("""
      class Foo {
      \t#if never
      \tpublic static function dead(a:Int):Void {
      \t\ttrace(a);
      \t}
      \t#end
      }""");

    assertNotNull(PsiTreeUtil.findChildOfType(body, HaxeMethodDeclaration.class),
                  "the dead method must parse into real member PSI");
  }

  @Test
  @DisplayName("statement level branch parses as statements")
  public void testStatementLevelBranchParsesAsStatements() {
    HaxeInactiveBody body = inactiveBody("""
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\tvar x = 1;
      \t\ttrace(x);
      \t\t#end
      \t}
      }""");

    assertNotNull(PsiTreeUtil.findChildOfType(body, HaxeLocalVarDeclarationList.class),
                  "dead statements must parse into real statement PSI");
  }

  @Test
  @DisplayName("token soup fallback for partial constructs")
  public void testTokenSoupFallbackForPartialConstructs() {
    HaxeInactiveBody body = inactiveBody("""
      class Foo {
      \tfunction f():Void {
      \t\tvar x = 1 #if never < #else > #end 2;
      \t}
      }""");

    assertNull(PsiTreeUtil.findChildOfType(body, com.intellij.psi.PsiErrorElement.class),
               "the soup fallback carries no error elements");
    assertTrue(body.getNode().getFirstChildNode() != null, "the soup still exposes the raw tokens");
  }

  @Test
  @DisplayName("dead code produces no error or warning markers")
  public void testDeadCodeProducesNoErrorOrWarningMarkers() {
    String source = """
      class Foo {
      \t#if never
      \tthis is not even haxe !!
      \tfunction broken(:Void {
      \t#end
      \tfunction live():Void {}
      }""";
    myFixture.configureByText("Foo.hx", source);

    var errors = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR);
    assertTrue(errors.isEmpty(), "inactive branches are analysis-exempt: " + errors);
  }

  @Test
  @DisplayName("dead branch containing a doc comment still parses")
  public void testDeadBranchContainingADocCommentStillParses() {
    // the doc comment is a NESTED chameleon; grading must not force it to
    // parse while the candidate tree is detached (the platform forbids that)
    HaxeInactiveBody body = inactiveBody("""
      class Foo {
      \t#if never
      \t/**
      \t\tdocs for the dead method
      \t**/
      \tpublic function dead():Void {}
      \t#end
      }""");

    assertNotNull(PsiTreeUtil.findChildOfType(body, HaxeMethodDeclaration.class),
                  "member grading must survive a nested doc comment");
  }

  @Test
  @DisplayName("unused inspection ignores dead module fields")
  public void testUnusedInspectionIgnoresDeadModuleFields() {
    myFixture.enableInspections(new com.intellij.plugins.haxe.ide.inspections.unused.HaxeUnusedFieldInspection());
    String source = """
      package;
      #if never
      /**
      \tdocs
      **/
      var deadModuleField:String;
      #end
      class Foo {
      \tfunction f():Void {}
      }""";
    myFixture.configureByText("Foo.hx", source);

    var warnings = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING).stream()
      .filter(info -> info.getStartOffset() >= source.indexOf("#if") && info.getEndOffset() <= source.indexOf("#end"))
      .toList();
    assertTrue(warnings.isEmpty(), "dead fields get no unused markers and no usage searches: " + warnings);
  }

  @Test
  @DisplayName("dead code gets dimmed per token colors")
  public void testDeadCodeGetsDimmedPerTokenColors() {
    String source = """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\tvar x = "dead";
      \t\t#end
      \t}
      }""";
    myFixture.configureByText("Foo.hx", source);

    int varStart = source.indexOf("var x");
    var dimmed = myFixture.doHighlighting().stream()
      .filter(info -> info.getStartOffset() == varStart && info.getEndOffset() == varStart + 3)
      .filter(info -> info.forcedTextAttributes != null)
      .toList();
    assertFalse(dimmed.isEmpty(), "the dead 'var' keyword must carry dimmed enforced attributes");
    assertNotNull(dimmed.get(0).forcedTextAttributes.getForegroundColor(), "dimming blends the foreground");
  }

  @Test
  @DisplayName("dead identifiers without a dedicated color dim too")
  public void testDeadIdentifiersWithoutADedicatedColorDimToo() {
    // plainRef has no syntax-highlighter key; it must still get dimmed
    // default-text attributes instead of the flat dead-code color
    String source = """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\ttrace(plainRef);
      \t\t#end
      \t}
      }""";
    myFixture.configureByText("Foo.hx", source);

    int refStart = source.indexOf("plainRef");
    boolean refDimmed = myFixture.doHighlighting().stream()
      .anyMatch(info -> info.getStartOffset() == refStart
                        && info.getEndOffset() == refStart + "plainRef".length()
                        && info.forcedTextAttributes != null);
    assertTrue(refDimmed, "every dead leaf gets dim attributes - nothing may keep the flat CC color");
  }

  @Test
  @DisplayName("dead doc comment dims as one block without markup accents")
  public void testDeadDocCommentDimsAsOneBlockWithoutMarkupAccents() {
    String source = """
      class Foo {
      \t#if never
      \t/**
      \t\tDead docs.
      \t\t@param value ignored
      \t**/
      \tfunction dead(value:Int):Void {}
      \t#end
      }""";
    myFixture.configureByText("Foo.hx", source);
    var infos = myFixture.doHighlighting();

    int docStart = source.indexOf("/**");
    int docEnd = source.indexOf("**/") + 3;
    boolean docDimmed = infos.stream()
      .anyMatch(info -> info.getStartOffset() == docStart && info.getEndOffset() == docEnd
                        && info.forcedTextAttributes != null);
    assertTrue(docDimmed, "the whole dead doc comment gets one dim annotation");

    var docTagKey = com.intellij.plugins.haxe.ide.highlight.HaxeSyntaxHighlighterColors.DOC_TAG;
    boolean tagAccented = infos.stream()
      .anyMatch(info -> info.getStartOffset() >= docStart && info.getEndOffset() <= docEnd
                        && docTagKey.equals(info.forcedTextAttributesKey));
    assertFalse(tagAccented, "@param markup must not punch through the dimmed dead doc");
  }

  @Test
  @DisplayName("dead members stay out of the stub tree")
  public void testDeadMembersStayOutOfTheStubTree() {
    PsiFile file = myFixture.configureByText("Foo.hx", """
      class Foo {
      \t#if never
      \tpublic function dead():Void {}
      \t#end
      \tpublic function live():Void {}
      }""");
    // force the chameleon to expand BEFORE stub building - the skip must hold even then
    assertNotNull(PsiTreeUtil.findChildOfType(file, HaxeInactiveBody.class).getFirstChild());

    var stubTree = ((com.intellij.psi.impl.source.PsiFileImpl)file).calcStubTree();
    boolean deadStubbed = stubTree.getPlainList().stream()
      .anyMatch(stub -> String.valueOf(stub).contains("dead"));
    assertFalse(deadStubbed, "inactive declarations must never reach the stub tree/indexes");
  }
}
