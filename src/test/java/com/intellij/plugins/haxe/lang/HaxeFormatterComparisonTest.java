package com.intellij.plugins.haxe.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
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
    applyHxformatDefaults(tempSettings);
    CodeStyleSettingsManager.getInstance(project).setTemporarySettings(tempSettings);
  }

  /**
   * Our settings equivalent of a DEFAULT hxformat.json (haxe-formatter 1.18).
   * The seed of a future hxformat.json importer: every assignment corresponds
   * to a config default named in doc/haxe-formatter-comparison.md.
   */
  private static void applyHxformatDefaults(CodeStyleSettings settings) {
    CodeStyleSettings.IndentOptions indent = settings.getIndentOptions(HaxeFileType.INSTANCE);
    // indentation.character="tab", tabWidth=4
    indent.USE_TAB_CHARACTER = true;
    indent.TAB_SIZE = 4;
    indent.INDENT_SIZE = 4;
    indent.CONTINUATION_INDENT_SIZE = 4;

    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    // wrapping.maxLineLength=160
    settings.setRightMargin(HaxeLanguage.INSTANCE, 160);
    // lineEnds.leftCurly=After / rightCurly=Both
    common.BRACE_STYLE = CommonCodeStyleSettings.END_OF_LINE;
    common.METHOD_BRACE_STYLE = CommonCodeStyleSettings.END_OF_LINE;
    // sameLine.ifElse/elseIf/doWhile/tryCatch=Same
    common.ELSE_ON_NEW_LINE = false;
    common.WHILE_ON_NEW_LINE = false;
    common.CATCH_ON_NEW_LINE = false;
    common.SPECIAL_ELSE_IF_TREATMENT = true;

    // whitespace keyword policies (After) and paren policies (none within)
    common.SPACE_BEFORE_IF_PARENTHESES = true;
    common.SPACE_BEFORE_WHILE_PARENTHESES = true;
    common.SPACE_BEFORE_FOR_PARENTHESES = true;
    common.SPACE_BEFORE_SWITCH_PARENTHESES = true;
    common.SPACE_BEFORE_CATCH_PARENTHESES = true;
    common.SPACE_BEFORE_METHOD_PARENTHESES = false;
    common.SPACE_BEFORE_METHOD_CALL_PARENTHESES = false;
    common.SPACE_WITHIN_METHOD_CALL_PARENTHESES = false;
    common.SPACE_WITHIN_METHOD_PARENTHESES = false;
    common.SPACE_WITHIN_IF_PARENTHESES = false;
    common.SPACE_WITHIN_WHILE_PARENTHESES = false;
    common.SPACE_WITHIN_FOR_PARENTHESES = false;
    common.SPACE_WITHIN_SWITCH_PARENTHESES = false;
    common.SPACE_WITHIN_CATCH_PARENTHESES = false;
    // whitespace.binopPolicy=Around (ours per operator class)
    common.SPACE_AROUND_ASSIGNMENT_OPERATORS = true;
    common.SPACE_AROUND_LOGICAL_OPERATORS = true;
    common.SPACE_AROUND_EQUALITY_OPERATORS = true;
    common.SPACE_AROUND_RELATIONAL_OPERATORS = true;
    common.SPACE_AROUND_ADDITIVE_OPERATORS = true;
    common.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = true;
    common.SPACE_AROUND_BITWISE_OPERATORS = true;
    common.SPACE_AROUND_SHIFT_OPERATORS = true;
    // whitespace.ternaryPolicy=Around
    common.SPACE_BEFORE_QUEST = true;
    common.SPACE_AFTER_QUEST = true;
    common.SPACE_BEFORE_COLON = true;
    common.SPACE_AFTER_COLON = true;
    // whitespace.commaPolicy=OnlyAfter
    common.SPACE_BEFORE_COMMA = false;
    common.SPACE_AFTER_COMMA = true;
    common.SPACE_AFTER_COMMA_IN_TYPE_ARGUMENTS = true;
    // whitespace.bracesConfig openingPolicy=Before
    common.SPACE_BEFORE_METHOD_LBRACE = true;
    common.SPACE_BEFORE_IF_LBRACE = true;
    common.SPACE_BEFORE_ELSE_LBRACE = true;
    common.SPACE_BEFORE_DO_LBRACE = true;
    common.SPACE_BEFORE_WHILE_LBRACE = true;
    common.SPACE_BEFORE_FOR_LBRACE = true;
    common.SPACE_BEFORE_SWITCH_LBRACE = true;
    common.SPACE_BEFORE_TRY_LBRACE = true;
    common.SPACE_BEFORE_CATCH_LBRACE = true;
    common.SPACE_BEFORE_ELSE_KEYWORD = true;
    common.SPACE_BEFORE_WHILE_KEYWORD = true;
    common.SPACE_BEFORE_CATCH_KEYWORD = true;

    // emptyLines: maxAnywhereInFile=1, afterPackage=1, beforeType=1,
    // betweenTypes=1, betweenVars=0, betweenFunctions=1, beginType=0,
    // endType=0 (afterLeftCurly/beforeRightCurly=Remove)
    common.KEEP_LINE_BREAKS = true;
    common.KEEP_BLANK_LINES_IN_CODE = 1;
    common.KEEP_BLANK_LINES_IN_DECLARATIONS = 1;
    common.KEEP_BLANK_LINES_BEFORE_RBRACE = 0;
    common.BLANK_LINES_AFTER_PACKAGE = 1;
    common.BLANK_LINES_AFTER_IMPORTS = 1;
    common.BLANK_LINES_AROUND_CLASS = 1;
    common.BLANK_LINES_AFTER_CLASS_HEADER = 0;
    common.BLANK_LINES_AROUND_FIELD = 0;
    common.BLANK_LINES_AROUND_METHOD = 1;
    common.BLANK_LINES_BEFORE_CLASS_END = 0;

    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    // whitespace.arrowFunctionsPolicy/functionTypeHaxe4Policy=Around
    haxe.SPACE_AROUND_ARROW = true;
    // whitespace.typeHintColonPolicy=None
    haxe.SPACE_BEFORE_TYPE_REFERENCE_COLON = false;
    haxe.SPACE_AFTER_TYPE_REFERENCE_COLON = false;
    // emptyLines.importAndUsing.beforeType=1
    haxe.MINIMUM_BLANK_LINES_AFTER_USING = 1;
  }

  /** Formats input.hx and compares against hxformat.hx — the parity claim for this rule. */
  private void doParityTest(String rule) throws Exception {
    String actual = formatInput(rule);
    assertEquals(fixture(rule, "hxformat.hx"), actual,
                 "our output must match haxe-formatter for " + rule);
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
    return FileUtil.toSystemIndependentName(text).replace("\r\n", "\n").replaceAll("\n+$", "");
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
}
