package com.intellij.plugins.haxe.lang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.plugins.haxe.ide.formatter.hxformat.HxformatDefaultProfile;
import com.intellij.plugins.haxe.ide.formatter.hxformat.HxformatJsonMapper;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hxformat.json mapping behind the scheme importer: overrides land on the
 * right settings fields and unsupported keys are reported, not dropped.
 */
@DisplayName("Formatting: hxformat.json import")
public class HxformatImportTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/formatter/comparison/";
  }

  @Test
  @DisplayName("defaults plus overrides")
  public void testDefaultsPlusOverrides() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    var root = new ObjectMapper().readTree("""
      {
        "indentation": { "character": "  ", "tabWidth": 2 },
        "wrapping": { "maxLineLength": 100 },
        "lineEnds": { "leftCurly": "before", "emptyCurly": "break" },
        "sameLine": { "ifElse": "next", "ifBody": "keep", "elseBody": "keep", "forBody": "keep",
                      "whileBody": "keep", "doWhileBody": "keep", "tryBody": "keep", "catchBody": "keep" },
        "whitespace": { "typeHintColonPolicy": "after", "typeCheckColonPolicy": "none", "unknownKey": true },
        "emptyLines": { "betweenSingleLineTypes": 2, "afterLeftCurly": "keep", "beforeBlocks": "keep",
                        "importAndUsing": { "betweenImports": 1, "betweenImportsLevel": "secondLevelPackage" } }
      }""");

    List<String> unsupported = HxformatJsonMapper.apply(settings, root);

    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    assertFalse(settings.getIndentOptions(HaxeFileType.INSTANCE).USE_TAB_CHARACTER);
    assertEquals(2, settings.getIndentOptions(HaxeFileType.INSTANCE).INDENT_SIZE);
    assertEquals(100, settings.getRightMargin(HaxeLanguage.INSTANCE));
    assertEquals(CommonCodeStyleSettings.NEXT_LINE, common.BRACE_STYLE);
    assertFalse(common.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE);
    assertTrue(common.ELSE_ON_NEW_LINE);
    assertEquals(HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP, haxe.IF_BODY_PLACEMENT);
    assertEquals(HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP, haxe.DO_WHILE_BODY_PLACEMENT);
    assertTrue(common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE, "no body breaks, so the mirroring checkbox flips back on");
    assertEquals(common.KEEP_BLANK_LINES_IN_CODE, haxe.KEEP_BLANK_LINES_AFTER_LBRACE);
    assertEquals(common.KEEP_BLANK_LINES_IN_CODE, haxe.KEEP_BLANK_LINES_AFTER_CASE_COLON);
    assertTrue(haxe.SPACE_AFTER_TYPE_REFERENCE_COLON);
    assertFalse(haxe.SPACE_BEFORE_TYPE_REFERENCE_COLON);
    assertFalse(haxe.SPACE_AROUND_TYPE_CHECK_COLON);
    assertEquals(2, haxe.KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES);
    assertEquals(1, haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS);
    assertEquals(2, haxe.IMPORT_GROUP_PACKAGE_DEPTH);
    assertEquals(List.of("whitespace.unknownKey"), unsupported);
  }

  /**
   * The settings UI encodes its combo values: "chop down if long" is
   * WRAP_ON_EVERY_ITEM|WRAP_AS_NEEDED (5), never bare WRAP_ON_EVERY_ITEM (4);
   * a scheme holding any other value renders as "Invalid option value" in
   * every wrap combo box. Sweep EVERY wrap/brace field the defaults produce.
   */
  @Test
  @DisplayName("wrap and brace values use the settings ui encoding")
  public void testWrapAndBraceValuesUseTheSettingsUiEncoding() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    var uiWrapValues = List.of(
      CommonCodeStyleSettings.DO_NOT_WRAP,
      CommonCodeStyleSettings.WRAP_AS_NEEDED,
      CommonCodeStyleSettings.WRAP_ALWAYS,
      CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM | CommonCodeStyleSettings.WRAP_AS_NEEDED);
    var uiBraceValues = List.of(
      CommonCodeStyleSettings.END_OF_LINE,
      CommonCodeStyleSettings.NEXT_LINE,
      CommonCodeStyleSettings.NEXT_LINE_SHIFTED,
      CommonCodeStyleSettings.NEXT_LINE_SHIFTED2,
      CommonCodeStyleSettings.NEXT_LINE_IF_WRAPPED);

    for (var field : CommonCodeStyleSettings.class.getFields()) {
      if (field.getType() != int.class) continue;
      String name = field.getName();
      if (name.endsWith("_WRAP")) {
        assertTrue(uiWrapValues.contains(field.getInt(common)),
                   name + "=" + field.getInt(common) + " is not a settings-UI wrap value " + uiWrapValues);
      }
      if (name.endsWith("BRACE_STYLE")) {
        assertTrue(uiBraceValues.contains(field.getInt(common)),
                   name + "=" + field.getInt(common) + " is not a settings-UI brace value " + uiBraceValues);
      }
    }
  }

  /** The OpenFL library's config shape - lineEnds/sameLine only, wrapping untouched. */
  @Test
  @DisplayName("openfl shaped config")
  public void testOpenflShapedConfig() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    var root = new ObjectMapper().readTree("""
      {
        "excludes": ["/assets", "/node_modules"],
        "lineEnds": {
          "leftCurly": "both",
          "rightCurly": "both",
          "objectLiteralCurly": { "leftCurly": "after" }
        },
        "sameLine": {
          "ifBody": "same", "ifElse": "next", "doWhile": "next",
          "tryBody": "next", "tryCatch": "next"
        }
      }""");

    List<String> unsupported = HxformatJsonMapper.apply(settings, root);

    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    assertEquals(CommonCodeStyleSettings.NEXT_LINE, common.BRACE_STYLE);
    assertTrue(common.ELSE_ON_NEW_LINE);
    assertTrue(common.WHILE_ON_NEW_LINE);
    assertTrue(common.CATCH_ON_NEW_LINE);
    // ifBody=same joins if-bodies while tryBody=next keeps breaking; the
    // unset bodies stay at the Next default
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    assertEquals(HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE, haxe.IF_BODY_PLACEMENT);
    assertEquals(HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE, haxe.TRY_BODY_PLACEMENT);
    assertEquals(HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE, haxe.ELSE_BODY_PLACEMENT);
    assertFalse(common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE);
    int uiChop = CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM | CommonCodeStyleSettings.WRAP_AS_NEEDED;
    assertEquals(uiChop, common.ARRAY_INITIALIZER_WRAP);
    assertEquals(uiChop, common.METHOD_CALL_CHAIN_WRAP);
    assertTrue(unsupported.isEmpty(), "the openfl config maps completely, got: " + unsupported);
  }

  /** The sections added by the full-spec audit: wrapping rules, parens, brackets, clamp, line ends. */
  @Test
  @DisplayName("audited sections map")
  public void testAuditedSectionsMap() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    var root = new ObjectMapper().readTree("""
      {
        "lineEnds": { "lineEndCharacter": "LF" },
        "emptyLines": { "maxAnywhereInFile": 1, "betweenTypes": 3,
                        "importAndUsing": { "betweenImports": 1, "betweenImportsLevel": "all" } },
        "whitespace": {
          "parenConfig": { "callParens": { "openingPolicy": "around" } },
          "bracketConfig": { "arrayLiteralBrackets": { "openingPolicy": "around" } }
        },
        "wrapping": {
          "arrayWrap": { "defaultWrap": "noWrap" },
          "functionSignature": { "defaultWrap": "fillLine" },
          "methodChain": { "rules": [
            { "conditions": [ { "cond": "itemCount >= n", "value": 7 } ], "type": "fillLine" },
            { "conditions": [ { "cond": "exceedsMaxLineLength" } ], "type": "onePerLineAfterFirst" }
          ] },
          "multiVar": { "rules": [
            { "conditions": [ { "cond": "lineLength >= n", "value": 100 } ], "type": "onePerLineAfterFirst" }
          ] }
        }
      }""");

    List<String> unsupported = HxformatJsonMapper.apply(settings, root);

    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    assertEquals("\n", settings.LINE_SEPARATOR);
    assertEquals(1, common.BLANK_LINES_AROUND_CLASS, "betweenTypes=3 clamped by maxAnywhereInFile=1");
    assertEquals(1, haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS);
    assertEquals(99, haxe.IMPORT_GROUP_PACKAGE_DEPTH, "level=all separates every import");
    assertTrue(common.SPACE_BEFORE_METHOD_CALL_PARENTHESES);
    assertTrue(common.SPACE_WITHIN_METHOD_CALL_PARENTHESES);
    assertTrue(common.SPACE_WITHIN_BRACKETS);
    assertEquals(CommonCodeStyleSettings.DO_NOT_WRAP, common.ARRAY_INITIALIZER_WRAP);
    assertEquals(CommonCodeStyleSettings.WRAP_AS_NEEDED, common.METHOD_PARAMETERS_WRAP);
    int uiChop = CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM | CommonCodeStyleSettings.WRAP_AS_NEEDED;
    assertEquals(uiChop, common.METHOD_CALL_CHAIN_WRAP, "the exceedsMaxLineLength rule decides");
    assertEquals(100, haxe.MULTI_VAR_SPLIT_WIDTH, "the multiVar split width lifts from the config rule");
    List<String> sortedUnsupported = unsupported.stream().sorted().toList();
    assertEquals(List.of("wrapping.methodChain.rules (rule engine approximated by one policy)",
                         "wrapping.multiVar (only the line-length split is reproduced)"),
                 sortedUnsupported);
  }

  @Test
  @DisplayName("custom bool chain rules set the split thresholds")
  public void testCustomBoolChainRulesSetTheSplitThresholds() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    var root = new ObjectMapper().readTree("""
      {
        "wrapping": { "opBoolChain": { "rules": [
          { "conditions": [ { "cond": "totalItemLength <= n", "value": 90 } ], "type": "noWrap" },
          { "conditions": [ { "cond": "lineLength >= n", "value": 100 },
                            { "cond": "anyItemLength >= n", "value": 30 } ],
            "type": "onePerLineAfterFirst" },
          { "conditions": [ { "cond": "itemCount >= n", "value": 6 } ], "type": "onePerLineAfterFirst" }
        ] } }
      }""");

    HxformatJsonMapper.apply(settings, root);

    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    assertEquals(100, haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH);
    assertEquals(30, haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH);
    assertEquals(6, haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT);
    assertEquals(90, haxe.BOOL_CHAIN_SPLIT_TOTAL_LENGTH);
  }

  @Test
  @DisplayName("empty file imports completely")
  public void testEmptyFileImportsCompletely() throws Exception {
    CodeStyleSettings settings = freshDefaults();

    List<String> unsupported = HxformatJsonMapper.apply(settings, new ObjectMapper().readTree("{}"));

    assertTrue(unsupported.isEmpty(), "a default config maps completely");
  }

  @Test
  @DisplayName("explicit defaults import completely")
  public void testExplicitDefaultsImportCompletely() throws Exception {
    CodeStyleSettings settings = freshDefaults();
    var root = new ObjectMapper().readTree("""
      {
        "indentation": { "character": "tab", "tabWidth": 4, "conditionalPolicy": "aligned" },
        "wrapping": { "maxLineLength": 160 },
        "lineEnds": { "leftCurly": "after", "rightCurly": "both", "emptyCurly": "noBreak" },
        "sameLine": { "ifElse": "same", "ifBody": "next", "functionBody": "next", "anonFunctionBody": "same" },
        "whitespace": { "binopPolicy": "around", "commaPolicy": "onlyAfter",
                        "typeHintColonPolicy": "none", "typeCheckColonPolicy": "around",
                        "formatStringInterpolation": true },
        "emptyLines": { "maxAnywhereInFile": 1, "afterPackage": 1, "betweenSingleLineTypes": 0,
                        "importAndUsing": { "betweenImports": 0 } }
      }""");

    List<String> unsupported = HxformatJsonMapper.apply(settings, root);

    assertTrue(unsupported.isEmpty(), "spelled-out defaults map completely, got: " + unsupported);
  }

  private CodeStyleSettings freshDefaults() {
    CodeStyleSettings settings = projectSettingsCopy();
    HxformatDefaultProfile.apply(settings);
    return settings;
  }
}
