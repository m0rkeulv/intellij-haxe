package com.intellij.plugins.haxe.lang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeFormatterDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HaxeFormatterDefaults} against the tool's own word: the fixture is
 * what {@code haxelib run formatter --default-config <file>} writes (the file
 * must exist beforehand; regenerate it on a formatter upgrade).
 */
@DisplayName("Formatting: haxe-formatter defaults")
public class HaxeFormatterDefaultsTest extends HaxeLightFixtureTestCase {
  private JsonNode config;

  @Override
  protected String getBasePath() {
    return "/formatter/comparison/";
  }

  @BeforeEach
  void readDefaultConfig() throws IOException {
    config = new ObjectMapper().readTree(Path.of(getTestDataPath(), "default-hxformat.json").toFile());
  }

  @Test
  @DisplayName("indentation and margin")
  public void testIndentationAndMargin() {
    assertEquals("tab", config.at("/indentation/character").asText());
    assertEquals(HaxeFormatterDefaults.TAB_WIDTH, config.at("/indentation/tabWidth").asInt());
    assertEquals(HaxeFormatterDefaults.MAX_LINE_LENGTH, config.at("/wrapping/maxLineLength").asInt());
  }

  @Test
  @DisplayName("operator chain rules")
  public void testOperatorChainRules() {
    assertEquals(HaxeFormatterDefaults.BOOL_CHAIN_LINE_LENGTH, condition("opBoolChain", 0, "lineLength >= n"));
    assertEquals(HaxeFormatterDefaults.BOOL_CHAIN_ITEM_LENGTH, condition("opBoolChain", 0, "anyItemLength >= n"));
    assertEquals(HaxeFormatterDefaults.CHAIN_KEEP_ITEM_COUNT, condition("opBoolChain", 2, "itemCount <= n"));
    assertEquals(HaxeFormatterDefaults.BOOL_CHAIN_TOTAL_LENGTH, condition("opBoolChain", 3, "totalItemLength <= n"));
    assertEquals(HaxeFormatterDefaults.BOOL_CHAIN_ITEM_COUNT, condition("opBoolChain", 4, "itemCount >= n"));

    assertEquals(HaxeFormatterDefaults.ADD_CHAIN_LINE_LENGTH, condition("opAddSubChain", 0, "lineLength >= n"));
    assertEquals(HaxeFormatterDefaults.ADD_CHAIN_ITEM_LENGTH, condition("opAddSubChain", 0, "anyItemLength >= n"));
    assertEquals(HaxeFormatterDefaults.CHAIN_KEEP_ITEM_COUNT, condition("opAddSubChain", 2, "itemCount <= n"));
    assertEquals(HaxeFormatterDefaults.ADD_CHAIN_TOTAL_LENGTH, condition("opAddSubChain", 3, "totalItemLength <= n"));
    assertEquals(HaxeFormatterDefaults.ADD_CHAIN_ITEM_COUNT, condition("opAddSubChain", 4, "itemCount >= n"));
  }

  @Test
  @DisplayName("multi var rules")
  public void testMultiVarRules() {
    assertEquals(HaxeFormatterDefaults.MULTI_VAR_FILL_ITEM_LENGTH, condition("multiVar", 0, "anyItemLength <= n"));
    assertEquals(HaxeFormatterDefaults.MULTI_VAR_LINE_LENGTH, condition("multiVar", 1, "lineLength >= n"));
  }

  @Test
  @DisplayName("empty lines")
  public void testEmptyLines() {
    JsonNode emptyLines = config.get("emptyLines");
    assertEquals(HaxeFormatterDefaults.MAX_BLANK_LINES, emptyLines.get("maxAnywhereInFile").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_AFTER_PACKAGE, emptyLines.get("afterPackage").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_AFTER_IMPORTS, emptyLines.at("/importAndUsing/beforeType").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_IMPORTS, emptyLines.at("/importAndUsing/betweenImports").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_TYPES, emptyLines.get("betweenTypes").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES, emptyLines.get("betweenSingleLineTypes").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_AFTER_FILE_HEADER, emptyLines.get("afterFileHeaderComment").asInt());
    assertEquals("remove", emptyLines.get("afterLeftCurly").asText());
    assertEquals("remove", emptyLines.get("beforeRightCurly").asText());
    assertEquals("one", emptyLines.get("beforeDocCommentEmptyLines").asText());
    assertEquals("one", emptyLines.get("afterFieldsWithDocComments").asText());

    JsonNode classLines = emptyLines.get("classEmptyLines");
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BEGIN_TYPE, classLines.get("beginType").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_END_TYPE, classLines.get("endType").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_VARS, classLines.get("betweenVars").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_FUNCTIONS, classLines.get("betweenFunctions").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_VAR_GROUPS, classLines.get("afterStaticVars").asInt());
    assertEquals(HaxeFormatterDefaults.BLANK_LINES_BETWEEN_VAR_GROUPS, classLines.get("afterPrivateVars").asInt());
  }

  /** The value of a named condition in the construct's n-th wrapping rule. */
  private int condition(String construct, int rule, String cond) {
    for (JsonNode candidate : config.at("/wrapping/" + construct + "/rules").get(rule).get("conditions")) {
      if (cond.equals(candidate.get("cond").asText())) return candidate.get("value").asInt();
    }
    throw new AssertionError(construct + " rule " + rule + " has no condition " + cond);
  }
}
