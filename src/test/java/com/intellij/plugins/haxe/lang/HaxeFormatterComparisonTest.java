package com.intellij.plugins.haxe.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.settings.HxformatCodeStyle;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Three-way comparison against HaxeCheckstyle's haxe-formatter (the vshaxe
 * formatter): each rule fixture holds a deliberately misformatted input.hx
 * and the real tool's output hxformat.hx (see the fixture README for
 * regeneration). Our settings are configured to the haxe-formatter DEFAULTS;
 * parity rules assert byte equality with the tool, the rest pin plugin.hx as
 * the divergence-documenting baseline.
 */
@DisplayName("Formatting: haxe-formatter comparison")
public class HaxeFormatterComparisonTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/formatter/comparison/";
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Project project = getProject();
    CodeStyleSettings tempSettings = CodeStyleSettingsManager.getSettings(project).clone();
    // the production defaults mapping IS the parity profile - the fixtures
    // guard the hxformat.json importer's baseline
    HxformatCodeStyle.applyDefaults(tempSettings);
    CodeStyleSettingsManager.getInstance(project).setTemporarySettings(tempSettings);
  }

  @Test
  @DisplayName("spacing keyword parens")
  public void testSpacingKeywordParens() throws Exception {
    doParityTest("spacing-keyword-parens");
  }

  @Test
  @DisplayName("spacing operators")
  public void testSpacingOperators() throws Exception {
    doParityTest("spacing-operators");
  }

  @Test
  @DisplayName("spacing ternary and colons")
  public void testSpacingTernaryAndColons() throws Exception {
    doParityTest("spacing-ternary-and-colons");
  }

  @Test
  @DisplayName("spacing commas")
  public void testSpacingCommas() throws Exception {
    doParityTest("spacing-commas");
  }

  @Test
  @DisplayName("spacing within parens")
  public void testSpacingWithinParens() throws Exception {
    doParityTest("spacing-within-parens");
  }

  @Test
  @DisplayName("braces placement")
  public void testBracesPlacement() throws Exception {
    doParityTest("braces-placement");
  }

  @Test
  @DisplayName("blank lines members")
  public void testBlankLinesMembers() throws Exception {
    doParityTest("blank-lines-members");
  }

  @Test
  @DisplayName("blank lines header")
  public void testBlankLinesHeader() throws Exception {
    doParityTest("blank-lines-header");
  }

  @Test
  @DisplayName("indentation tabs")
  public void testIndentationTabs() throws Exception {
    doParityTest("indentation-tabs");
  }

  @Test
  @DisplayName("function types")
  public void testFunctionTypes() throws Exception {
    doParityTest("function-types");
  }

  @Test
  @DisplayName("array literal wrap")
  public void testArrayLiteralWrap() throws Exception {
    doParityTest("array-literal-wrap");
  }

  @Test
  @DisplayName("map literal wrap")
  public void testMapLiteralWrap() throws Exception {
    doParityTest("map-literal-wrap");
  }

  @Test
  @DisplayName("object literal wrap")
  public void testObjectLiteralWrap() throws Exception {
    doParityTest("object-literal-wrap");
  }

  @Test
  @DisplayName("method chain wrap")
  public void testMethodChainWrap() throws Exception {
    doParityTest("method-chain-wrap");
  }

  @Test
  @DisplayName("empty curly")
  public void testEmptyCurly() throws Exception {
    doParityTest("empty-curly");
  }

  @Test
  @DisplayName("same line bodies")
  public void testSameLineBodies() throws Exception {
    doParityTest("same-line-bodies");
  }

  @Test
  @DisplayName("extends implements wrap")
  public void testExtendsImplementsWrap() throws Exception {
    doParityTest("extends-implements-wrap");
  }

  @Test
  @DisplayName("bracket spacing")
  public void testBracketSpacing() throws Exception {
    doParityTest("bracket-spacing");
  }

  @Test
  @DisplayName("type param spacing")
  public void testTypeParamSpacing() throws Exception {
    doParityTest("type-param-spacing");
  }

  @Test
  @DisplayName("function body next line")
  public void testFunctionBodyNextLine() throws Exception {
    doParityTest("function-body-next-line");
  }

  @Test
  @DisplayName("type check colon")
  public void testTypeCheckColon() throws Exception {
    doParityTest("type-check-colon");
  }

  @Test
  @DisplayName("single line types")
  public void testSingleLineTypes() throws Exception {
    doParityTest("single-line-types");
  }

  @Test
  @DisplayName("metadata parens")
  public void testMetadataParens() throws Exception {
    doParityTest("metadata-parens");
  }

  @Test
  @DisplayName("typedef extension")
  public void testTypedefExtension() throws Exception {
    doParityTest("typedef-extension");
  }

  @Test
  @DisplayName("import blanks")
  public void testImportBlanks() throws Exception {
    doParityTest("import-blanks");
  }

  @Test
  @DisplayName("conditional compilation")
  public void testConditionalCompilation() throws Exception {
    doParityTest("conditional-compilation");
  }

  @Test
  @DisplayName("expression same line")
  public void testExpressionSameLine() throws Exception {
    doParityTest("expression-same-line");
  }

  @Test
  @DisplayName("multi var and patterns")
  public void testMultiVarAndPatterns() throws Exception {
    doParityTest("multi-var-and-patterns");
  }

  @Test
  @DisplayName("doc comment blanks")
  public void testDocCommentBlanks() throws Exception {
    doParityTest("doc-comment-blanks");
  }

  @Test
  @DisplayName("file header comment")
  public void testFileHeaderComment() throws Exception {
    doParityTest("file-header-comment");
  }

  @Test
  @DisplayName("return join")
  public void testReturnJoin() throws Exception {
    doParityTest("return-join");
  }

  @Test
  @DisplayName("comment blanks")
  public void testCommentBlanks() throws Exception {
    doParityTest("comment-blanks");
  }

  @Test
  @DisplayName("string interpolation")
  public void testStringInterpolation() throws Exception {
    doParityTest("string-interpolation");
  }

  @Test
  @DisplayName("openfl braces")
  public void testOpenflBraces() throws Exception {
    // lineEnds.leftCurly=both, objectLiteralCurly.leftCurly=after (fixture
    // hxformat.json): Allman blocks with cuddled object literals
    doParityTest("openfl-braces", settings -> {
      CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
      common.BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
      common.METHOD_BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
    });
  }

  @Test
  @DisplayName("import grouping")
  public void testImportGrouping() throws Exception {
    // betweenImports=1, betweenImportsLevel=firstLevelPackage (fixture hxformat.json)
    doParityTest("import-grouping", settings -> {
      HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
      haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS = 1;
      haxe.IMPORT_GROUP_PACKAGE_DEPTH = 1;
    });
  }

  @Test
  @DisplayName("doc comment indent")
  public void testDocCommentIndent() throws Exception {
    // interior doc lines: haxedoc body at comment+1 with markdown depth kept,
    // column-0 wrap clamped to body, starred style aligned one space in
    doParityTest("doc-comment-indent");
  }

  @Test
  @DisplayName("multiline comments")
  public void testMultilineComments() throws Exception {
    // interior lines of plain /*..*/ comments: common margin removed,
    // middles one level deeper, star rails aligned under the opener,
    // empty interior lines left empty
    doParityTest("multiline-comments");
  }

  @Test
  @DisplayName("conditional inactive branches")
  public void testConditionalInactiveBranches() throws Exception {
    // inactive branches format with the SAME rules as active code (the
    // reference never distinguishes them); token-soup branches like the
    // lone-operator case are preserved verbatim - as the reference does
    doParityTest("conditional-inactive");
  }

  /** Formats input.hx and compares against hxformat.hx — the parity claim for this rule. */
  private void doParityTest(String rule) throws Exception {
    String actual = formatInput(rule);
    assertEquals(fixture(rule, "hxformat.hx"), actual,
                 "our output must match haxe-formatter for " + rule);
  }

  /**
   * Parity against a NON-default hxformat option: the fixture directory holds
   * the hxformat.json used to regenerate hxformat.hx, and the tweak applies
   * the equivalent change on top of our defaults profile.
   */
  private void doParityTest(String rule, Consumer<CodeStyleSettings> tweak) throws Exception {
    Project project = getProject();
    CodeStyleSettings tempSettings = CodeStyleSettingsManager.getSettings(project).clone();
    HxformatCodeStyle.applyDefaults(tempSettings);
    tweak.accept(tempSettings);
    CodeStyleSettingsManager.getInstance(project).setTemporarySettings(tempSettings);
    doParityTest(rule);
  }

  /**
   * Formats input.hx and compares against the pinned plugin.hx — for rules
   * NOT yet at parity; the plugin.hx/hxformat.hx diff documents the gap.
   * A missing plugin.hx is created from the actual output and the test fails
   * once, like the classic formatter tests.
   */
  @SuppressWarnings("unused") // the parity workflow flips rules here while a gap is open
  private void doPinnedTest(String rule) throws Exception {
    String actual = formatInput(rule);
    Path pinned = Path.of(getTestDataPath(), rule, "plugin.hx");
    if (!Files.exists(pinned)) {
      Files.writeString(pinned, actual + "\n");
      fail("No pinned output found. File " + pinned + " created.");
    }
    assertEquals(fixture(rule, "plugin.hx"), actual, "pinned plugin output changed for " + rule);
  }

  @NotNull
  private String formatInput(String rule) {
    myFixture.configureByFile(rule + "/input.hx");
    Runnable reformat = () -> CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
    WriteCommandAction.runWriteCommandAction(getProject(), reformat);
    return normalize(myFixture.getFile().getText());
  }

  @NotNull
  private String fixture(String rule, String name) throws IOException {
    return normalize(Files.readString(Path.of(getTestDataPath(), rule, name)));
  }

  /** Unifies line endings and drops the trailing newline — the IDE manages end-of-file newlines at save time, not in the formatter. */
  @NotNull
  private static String normalize(@NotNull String text) {
    return text.replace("\r\n", "\n").replaceAll("\n+$", "");
  }
}
