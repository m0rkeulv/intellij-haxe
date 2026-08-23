package com.intellij.plugins.haxe.lang;

import com.intellij.lexer.Lexer;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.lang.lexer.HaxeLexer;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.psi.tree.IElementType;
import com.intellij.plugins.haxe.lang.psi.HaxeLocalVarDeclarationList;
import com.intellij.plugins.haxe.lang.psi.HaxeMethodDeclaration;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeInactiveBody;
import com.intellij.plugins.haxe.lang.util.HaxeConditionalExpression;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Behavior of INACTIVE conditional branches. The parser lexes a dead region
 * as ONE PPBODY blob (lazily parsed into real PSI), the editor highlighter
 * keeps real token types there instead - and everything downstream builds on
 * that split: analysis exemption, dimmed colors, best-effort completion,
 * token-stream editing mechanics and incremental-relex restartability.
 */
@DisplayName("Conditional compilation: inactive branches")
public class HaxeInactiveBranchesTest extends HaxeLightFixtureTestCase {

  // required by the base; every fixture here is inline configureByText
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

  private record LexStep(int start, int end, String type, int stateAfter) {}

  private List<LexStep> lexFrom(String source, int offset, Supplier<Lexer> lexerFactory) {
    Lexer lexer = lexerFactory.get();
    lexer.start(source, offset, source.length(), 0);
    List<LexStep> steps = new ArrayList<>();
    while (lexer.getTokenType() != null) {
      steps.add(new LexStep(lexer.getTokenStart(), lexer.getTokenEnd(), lexer.getTokenType().toString(), -1));
      lexer.advance();
    }
    return steps;
  }

  /**
   * The editor's incremental highlighter restarts lexing only at boundaries
   * whose saved state equals a fresh lexer's - so at every such boundary a
   * cold start must reproduce the remaining stream exactly. A zero state
   * inside a conditional would make a restart lex dead code as live; the
   * CC_BLOCK lexer state exists to prevent that.
   */
  private void checkRestartAtEveryZeroStateBoundary(String source, boolean expectRestartableTail) {
    checkRestartAtEveryZeroStateBoundary(source, expectRestartableTail, () -> new HaxeLexer(getProject()));
    checkRestartAtEveryZeroStateBoundary(source, expectRestartableTail, () -> HaxeLexer.forHighlighting(getProject()));
  }

  private void checkRestartAtEveryZeroStateBoundary(String source, boolean expectRestartableTail, Supplier<Lexer> lexerFactory) {
    Lexer lexer = lexerFactory.get();
    lexer.start(source);
    List<LexStep> full = new ArrayList<>();
    while (lexer.getTokenType() != null) {
      String type = lexer.getTokenType().toString();
      int start = lexer.getTokenStart();
      int end = lexer.getTokenEnd();
      lexer.advance();
      full.add(new LexStep(start, end, type, lexer.getState()));
    }
    assertFalse(full.isEmpty(), "the source must lex to something");

    int restartableBoundaries = 0;
    for (int i = 0; i < full.size() - 1; i++) {
      if (full.get(i).stateAfter() != 0) continue;
      restartableBoundaries++;

      int offset = full.get(i).end();
      List<LexStep> restarted = lexFrom(source, offset, lexerFactory);
      List<LexStep> remainder = full.subList(i + 1, full.size());
      assertEquals(remainder.size(), restarted.size(), "restart at " + offset + ": token count diverged");
      for (int j = 0; j < remainder.size(); j++) {
        LexStep expected = remainder.get(j);
        LexStep actual = restarted.get(j);
        boolean sameToken = expected.start() == actual.start()
                            && expected.end() == actual.end()
                            && expected.type().equals(actual.type());
        assertTrue(sameToken, "restart at " + offset + " diverged: expected " + expected + " but got " + actual);
      }
    }
    if (expectRestartableTail) {
      assertTrue(restartableBoundaries > 0, "expected at least one restartable boundary - otherwise this source proves nothing");
    }
  }

  // (name, source, whether top-level code after the conditional must be restartable)
  static final List<Arguments> RESTART_SOURCES = List.of(
    arguments("top-level dead branch", """
      class A {}
      #if never
      class Dead { var x:Int; }
      #else
      class Live {}
      #end
      class B {}""", true),
    arguments("nested conditionals", """
      #if never
      #if js
      var a = 1;
      #end
      var b = 2;
      #end
      class After {}""", true),
    arguments("elseif chain in class body", """
      class C {
      \t#if never
      \tvar a:Int;
      \t#elseif never_either
      \tvar b:Int;
      \t#else
      \tvar c:Int;
      \t#end
      }
      class D {}""", true),
    arguments("strings and templates around a dead branch", """
      class E {
      \tfunction f():Void {
      \t\tvar s = "before";
      \t\t#if never
      \t\tvar t = 'tmp ${1 + 1}';
      \t\t#end
      \t\tvar u = "after";
      \t}
      }""", true),
    arguments("inline expression conditional", """
      class F {
      \tfunction f():Void {
      \t\tvar mode = #if debug "d" #else "r" #end;
      \t\tvar tail = 1;
      \t}
      }""", true),
    arguments("cross branch operator soup", """
      class G {
      \tfunction f():Void {
      \t\tvar x = 1 #if never < #else > #end 2;
      \t}
      }
      class H {}""", true),
    // an unterminated #if keeps the file in CC state to EOF - nothing to restart, but it must not diverge either
    arguments("missing #end at eof", """
      class I {}
      #if never
      var dead:Int;""", false),
    arguments("stray #end without #if", """
      class J {}
      #end
      class K {}""", true));

  @ParameterizedTest(name = "{0}")
  @FieldSource("RESTART_SOURCES")
  @DisplayName("lexer restart at zero state boundaries reproduces the stream")
  public void testLexerRestartAtZeroStateBoundariesReproducesTheStream(String name, String source, boolean expectRestartableTail) {
    checkRestartAtEveryZeroStateBoundary(source, expectRestartableTail);
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
  @DisplayName("dead metadata dims instead of losing its color")
  public void testDeadMetadataDimsInsteadOfLosingItsColor() {
    // metadata is its own PSI language - the dim annotator needs its
    // HaxeMetadata registration or dead @:meta renders as plain text
    String source = """
      class Foo {
      \t#if never
      \t@:keep var deadField:Int;
      \t#end
      }""";
    myFixture.configureByText("Foo.hx", source);

    int metaStart = source.indexOf("@:keep");
    boolean metaDimmed = myFixture.doHighlighting().stream()
      .anyMatch(info -> info.getStartOffset() >= metaStart
                        && info.getEndOffset() <= metaStart + "@:keep".length()
                        && info.forcedTextAttributes != null);
    assertTrue(metaDimmed, "dead metadata tokens must carry dim attributes");
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
  @DisplayName("highlighting lexer keeps real token types in dead code")
  public void testHighlightingLexerKeepsRealTokenTypesInDeadCode() {
    // the editor's token-stream mechanics (brace matching/auto-close, enter
    // between braces) are blind wherever the lexer flattens to PPBODY
    String source = """
      class Foo {
      \t#if never
      \tfunction dead():Void {}
      \t#end
      }""";
    Lexer lexer = HaxeLexer.forHighlighting(getProject());
    lexer.start(source);
    List<IElementType> types = new ArrayList<>();
    while (lexer.getTokenType() != null) {
      types.add(lexer.getTokenType());
      lexer.advance();
    }

    assertFalse(types.contains(HaxeTokenTypeSets.PPBODY), "dead code must keep real token types: " + types);
    assertTrue(types.contains(HaxeTokenTypes.PPIF), "directives keep their identity");
    long curlies = types.stream().filter(t -> t == HaxeTokenTypes.PLCURLY).count();
    assertEquals(2, curlies, "brace tokens must be visible to the brace matcher");
  }

  @Test
  @DisplayName("typing a brace in a dead branch auto closes it")
  public void testTypingABraceInADeadBranchAutoClosesIt() {
    myFixture.configureByText("Foo.hx", """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\tif (true)<caret>
      \t\t#end
      \t}
      }""");

    myFixture.type('{');

    String text = myFixture.getEditor().getDocument().getText();
    assertTrue(text.contains("if (true){}"), "the closing brace must auto-insert:\n" + text);
  }

  @Test
  @DisplayName("enter between dead braces splits and indents")
  public void testEnterBetweenDeadBracesSplitsAndIndents() {
    myFixture.configureByText("Foo.hx", """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\tif (true){<caret>}
      \t\t#end
      \t}
      }""");

    myFixture.type('\n');

    String text = myFixture.getEditor().getDocument().getText();
    assertFalse(text.contains("{}"), "the brace pair must split across lines:\n" + text);
    int caretOffset = myFixture.getCaretOffset();
    int caretLineStart = text.lastIndexOf('\n', caretOffset - 1) + 1;
    String caretIndent = text.substring(caretLineStart, caretOffset);
    assertTrue(caretIndent.isBlank() && caretIndent.length() > "\t\t".length(),
               "the new line indents deeper than the if line, got '" + caretIndent + "' in:\n" + text);
  }

  @Test
  @DisplayName("keyword completion works inside a dead branch")
  public void testKeywordCompletionWorksInsideADeadBranch() {
    myFixture.configureByText("Foo.hx", """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\ti<caret>
      \t\t#end
      \t}
      }""");

    myFixture.completeBasic();
    List<String> lookups = myFixture.getLookupElementStrings();
    assertNotNull(lookups, "completion must run inside the parsed dead branch");
    // keyword items carry template text ("if (<CARET>)"), so match by prefix
    boolean ifOffered = lookups.stream().anyMatch(lookup -> lookup.startsWith("if"));
    assertTrue(ifOffered, "statement keywords must be offered: " + lookups);
    assertTrue(lookups.contains("function "), "member keywords must be offered: " + lookups);
  }

  @Test
  @DisplayName("local variables complete inside a dead branch")
  public void testLocalVariablesCompleteInsideADeadBranch() {
    myFixture.configureByText("Foo.hx", """
      class Foo {
      \tfunction f():Void {
      \t\t#if never
      \t\tvar counter = 1;
      \t\tcou<caret>
      \t\t#end
      \t}
      }""");

    myFixture.completeBasic();
    // the local is the single match, so completion auto-inserts it - the
    // second occurrence in the document is the proof it resolved
    long occurrences = myFixture.getEditor().getDocument().getText().split("counter", -1).length - 1;
    assertEquals(2, occurrences, "same-tree locals must resolve for completion");
  }

  // (defines, condition, whether the #if branch is active) - every row was
  // verified against haxe 4.3.7; rows the compiler hard-errors on (invalid
  // version literal, unknown function, non-literal argument, float-vs-version
  // compare) evaluate to an inactive branch here
  static final List<Arguments> CONDITION_EVALUATIONS = List.of(
    arguments("hl_ver=1.12.0", "(hl_ver >= version(\"1.12.0\"))", true),
    arguments("hl_ver=1.13.0", "(hl_ver >= version(\"1.12.0\"))", true),
    // a define VALUE wrapped in quotes (as typed into the overrides UI) de-quotes
    arguments("hl_ver=\"1.13.1\"", "(hl_ver >= version(\"1.12.0\"))", true),
    arguments("hl_ver=1.11.0", "(hl_ver >= version(\"1.12.0\"))", false),
    arguments("haxe=4.3.7", "(haxe >= version(\"4.1.0\"))", true),
    arguments("haxe_ver=4.307", "(haxe_ver >= 4.1)", true),
    arguments("v=1.2", "(v == 1.2)", true),
    arguments("", "(version(\"1.10.0\") > version(\"1.9.0\"))", true),
    arguments("", "(version(\"1.0.0-rc.1\") < version(\"1.0.0\"))", true),
    arguments("", "(version(\"1.0.0-alpha.2\") < version(\"1.0.0-alpha.10\"))", true),
    arguments("", "(undef_thing > 3)", false),
    arguments("hl_ver=1.12", "(hl_ver >= version(\"1.12.0\"))", false),
    arguments("", "(version(\"banana\") > version(\"1.0.0\"))", false),
    arguments("", "(version(\"1.0.0+build5\") == version(\"1.0.0\"))", false),
    arguments("", "(foo(\"x\"))", false),
    arguments("hl_ver=1.12.0", "(version(hl_ver) > version(\"1.0.0\"))", false));

  @ParameterizedTest(name = "{1} with [{0}]")
  @FieldSource("CONDITION_EVALUATIONS")
  @DisplayName("condition evaluation matches the compiler")
  public void testConditionEvaluationMatchesTheCompiler(String defines, String condition, boolean active) {
    getProject().putUserData(HaxeConditionalExpression.DEFINES_KEY, defines);
    try {
      PsiFile file = myFixture.configureByText("Foo.hx", """
        class Foo {
        \t#if %s
        \tvar marker:Int;
        \t#end
        }""".formatted(condition));

      boolean branchLive = PsiTreeUtil.findChildOfType(file, HaxeInactiveBody.class) == null;
      assertEquals(active, branchLive, "wrong activeness for " + condition + " with defines [" + defines + "]");
    }
    finally {
      getProject().putUserData(HaxeConditionalExpression.DEFINES_KEY, null);
    }
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
