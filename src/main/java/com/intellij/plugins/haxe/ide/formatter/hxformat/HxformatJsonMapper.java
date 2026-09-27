package com.intellij.plugins.haxe.ide.formatter.hxformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.plugins.haxe.HaxeCodeStyleBundle;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Applies an hxformat.json's keys on top of {@link HxformatDefaultProfile}:
 * walks the known key paths, consuming what it maps; the rest is reported
 * as the config paths that could not be honored.
 */
public final class HxformatJsonMapper {
  // WhitespacePolicy values that put a space AFTER the token / BEFORE it
  private static final Set<String> SPACE_AFTER_POLICIES = Set.of("after", "onlyAfter", "around");
  private static final Set<String> SPACE_BEFORE_POLICIES = Set.of("before", "onlyBefore", "around");
  /** betweenImportsLevel value -> the grouping depth; "all" separates every import, which a full-path key reproduces. */
  private static final Map<String, Integer> IMPORT_LEVEL_DEPTHS = Map.of(
    "all", 99,
    "firstLevelPackage", 1,
    "secondLevelPackage", 2,
    "thirdLevelPackage", 3,
    "fourthLevelPackage", 4,
    "fifthLevelPackage", 5);

  private final CodeStyleSettings settings;
  private final CommonCodeStyleSettings common;
  private final HaxeCodeStyleSettings haxe;
  private final JsonNode root;
  private final Set<String> consumed = new LinkedHashSet<>();
  private final List<String> unsupported = new ArrayList<>();
  // emptyLines.maxAnywhereInFile clamps EVERY other blank-line count
  private int blankLinesClamp = Integer.MAX_VALUE;

  /** Returns the config paths present in the file that could not be honored. */
  public static List<String> apply(@NotNull CodeStyleSettings settings, @NotNull JsonNode root) {
    HxformatJsonMapper mapper = new HxformatJsonMapper(settings, root);
    mapper.applyAll();
    return mapper.unsupported;
  }

  private HxformatJsonMapper(CodeStyleSettings settings, JsonNode root) {
    this.settings = settings;
    this.common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    this.haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    this.root = root;
  }

  private void applyAll() {
    // file-exclusion globs for their CLI - no code-style meaning in the IDE
    consumed.add("excludes");
    // editor metadata naming a JSON schema, not a formatter setting
    consumed.add("$schema");
    acceptOnly("disableFormatting", "false");
    applyIndentation();
    applyWrapping();
    applyLineEnds();
    applySameLine();
    applyWhitespace();
    applyEmptyLines();
    collectLeftovers(root, "");
  }

  private void applyIndentation() {
    String character = str("indentation.character");
    if (character != null) {
      settings.getIndentOptions(HaxeFileType.INSTANCE).USE_TAB_CHARACTER = "tab".equals(character);
    }
    Integer tabWidth = intVal("indentation.tabWidth");
    if (tabWidth != null) {
      CodeStyleSettings.IndentOptions indent = settings.getIndentOptions(HaxeFileType.INSTANCE);
      indent.TAB_SIZE = tabWidth;
      indent.INDENT_SIZE = tabWidth;
      indent.CONTINUATION_INDENT_SIZE = tabWidth * 2;
    }
    acceptOnly("indentation.conditionalPolicy", "aligned");
    acceptOnly("indentation.indentCaseLabels", "true");
    acceptOnly("indentation.indentObjectLiteral", "true");
    acceptOnly("indentation.indentComplexValueExpressions", "false");
    acceptOnly("indentation.trailingWhitespace", "false");
  }

  private void applyWrapping() {
    Integer margin = intVal("wrapping.maxLineLength");
    if (margin != null) {
      settings.setRightMargin(HaxeLanguage.INSTANCE, margin);
    }
    // array matrices are never column-aligned
    acceptOnly("wrapping.arrayMatrixWrap", "noMatrixWrap");
    wrapConstruct("wrapping.arrayWrap", value -> common.ARRAY_INITIALIZER_WRAP = value);
    wrapConstruct("wrapping.mapWrap", value -> common.ARRAY_INITIALIZER_WRAP = value);
    wrapConstruct("wrapping.objectLiteral", value -> common.ARRAY_INITIALIZER_WRAP = value);
    wrapConstruct("wrapping.methodChain", value -> common.METHOD_CALL_CHAIN_WRAP = value);
    wrapConstruct("wrapping.implementsExtends", value -> common.EXTENDS_LIST_WRAP = value);
    wrapConstruct("wrapping.functionSignature", value -> common.METHOD_PARAMETERS_WRAP = value);
    wrapConstruct("wrapping.anonFunctionSignature", value -> common.METHOD_PARAMETERS_WRAP = value);
    wrapConstruct("wrapping.callParameter", value -> common.CALL_PARAMETERS_WRAP = value);
    // the &&/|| and +/- chains follow their own rule engine fed from the
    // rule thresholds (HaxeOperatorChainRules); no single wrap policy stands
    // in for them, and BINARY_OPERATION_WRAP stays off so a margin wrap
    // never competes with a chopped method chain
    ChainSetters boolChain = new ChainSetters(
      value -> haxe.BOOL_CHAIN_SPLIT_LINE_LENGTH = value,
      value -> haxe.BOOL_CHAIN_SPLIT_ITEM_LENGTH = value,
      value -> haxe.BOOL_CHAIN_SPLIT_ITEM_COUNT = value,
      value -> haxe.BOOL_CHAIN_SPLIT_TOTAL_LENGTH = value);
    ChainSetters addChain = new ChainSetters(
      value -> haxe.ADD_CHAIN_SPLIT_LINE_LENGTH = value,
      value -> haxe.ADD_CHAIN_SPLIT_ITEM_LENGTH = value,
      value -> haxe.ADD_CHAIN_SPLIT_ITEM_COUNT = value,
      value -> haxe.ADD_CHAIN_SPLIT_TOTAL_LENGTH = value);
    applyChainRules("wrapping.opBoolChain", boolChain);
    applyChainRules("wrapping.opAddSubChain", addChain);
    for (String construct : List.of("typeParameter", "metadataCallParameter", "casePattern", "anonType")) {
      String path = "wrapping." + construct;
      if (node(path) != null) {
        markConsumedSubtree(path);
        unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.no.wrap.target", path));
      }
    }
    if (node("wrapping.multiVar") != null) {
      markConsumedSubtree("wrapping.multiVar");
      // the split width lifts from a matching rule; the length-based JOIN
      // of short multi-vars stays unreproduced
      JsonNode multiVarRules = node("wrapping.multiVar.rules");
      if (multiVarRules != null && multiVarRules.isArray()) {
        for (JsonNode rule : multiVarRules) {
          String type = rule.path("type").asText("");
          Integer line = conditionValue(rule, "lineLength >= n");
          if ("onePerLineAfterFirst".equals(type) && line != null) {
            haxe.MULTI_VAR_SPLIT_WIDTH = line;
          }
          Integer shortItem = conditionValue(rule, "anyItemLength <= n");
          if ("fillLine".equals(type) && shortItem != null) {
            haxe.MULTI_VAR_FILL_ITEM_LENGTH = shortItem;
          }
        }
      }
      unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.multi.var"));
    }
  }

  /**
   * The tool picks the FIRST rule whose conditions all hold; a construct
   * here has one wrap policy. The rule that fires on a margin overflow is
   * that policy - the guard rules preceding it in the tool's defaults
   * ("itemCount <= 3 and NOT exceeding -> noWrap") must not win; without an
   * overflow rule the first rule, then defaultWrap, decides.
   */
  private void wrapConstruct(String path, IntConsumer setter) {
    JsonNode construct = node(path);
    if (construct == null) return;
    markConsumedSubtree(path);
    String type = null;
    JsonNode rules = construct.get("rules");
    if (rules != null && rules.isArray() && !rules.isEmpty()) {
      for (JsonNode rule : rules) {
        JsonNode ruleType = rule.get("type");
        if (ruleType == null) continue;
        if (type == null) type = ruleType.asText();
        if (firesOnOverflow(rule)) {
          type = ruleType.asText();
          break;
        }
      }
      unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.rules", path));
    }
    if (type == null && construct.get("defaultWrap") != null) {
      type = construct.get("defaultWrap").asText();
    }
    if (type == null) return;
    setter.accept(switch (type) {
      case "onePerLine", "onePerLineAfterFirst", "equalNumber" -> HxformatDefaultProfile.UI_CHOP_DOWN;
      case "fillLine", "fillLineWithLeadingBreak" -> CommonCodeStyleSettings.WRAP_AS_NEEDED;
      default -> CommonCodeStyleSettings.DO_NOT_WRAP; // noWrap, keep
    });
  }

  /**
   * The rule carries an exceedsMaxLineLength condition asking for an
   * overflow: value 1, or no value (the tool reads any other value as "not
   * exceeding", the guard form).
   */
  private static boolean firesOnOverflow(JsonNode rule) {
    JsonNode conditions = rule.get("conditions");
    if (conditions == null || !conditions.isArray()) return false;
    for (JsonNode condition : conditions) {
      boolean overflow = "exceedsMaxLineLength".equals(condition.path("cond").asText(""))
                         && condition.path("value").asInt(1) == 1;
      if (overflow) return true;
    }
    return false;
  }

  private void applyLineEnds() {
    String leftCurly = str("lineEnds.leftCurly");
    if (leftCurly != null) {
      int style = "after".equals(leftCurly) ? CommonCodeStyleSettings.END_OF_LINE : CommonCodeStyleSettings.NEXT_LINE;
      common.BRACE_STYLE = style;
      common.METHOD_BRACE_STYLE = style;
    }
    String emptyCurly = str("lineEnds.emptyCurly");
    if (emptyCurly != null) {
      boolean collapse = "noBreak".equals(emptyCurly);
      common.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = collapse;
      common.KEEP_SIMPLE_METHODS_IN_ONE_LINE = collapse;
      common.KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE = collapse;
    }
    String lineEnd = str("lineEnds.lineEndCharacter");
    if (lineEnd != null) {
      settings.LINE_SEPARATOR = switch (lineEnd) {
        case "LF" -> "\n";
        case "CRLF" -> "\r\n";
        case "CR" -> "\r";
        default -> null; // auto - detect per file
      };
    }
    acceptOnly("lineEnds.rightCurly", "both");
    acceptOnly("lineEnds.sharp", "after");
    acceptOnly("lineEnds.caseColon", "after");
    acceptOnly("lineEnds.metadataType", "none");
    acceptOnly("lineEnds.metadataVar", "none");
    acceptOnly("lineEnds.metadataFunction", "none");
    acceptOnly("lineEnds.metadataOther", "none");
    // per-construct curly overrides: acceptable only when they restate what
    // the global import already produces (object literals are inherently
    // After-style here, whatever BRACE_STYLE says)
    String effectiveLeft = leftCurly == null ? "after" : leftCurly;
    String effectiveEmpty = emptyCurly == null ? "noBreak" : emptyCurly;
    for (String construct : List.of("blockCurly", "anonFunctionCurly", "anonTypeCurly", "typedefCurly")) {
      curlyOverride("lineEnds." + construct, effectiveLeft, effectiveEmpty);
    }
    curlyOverride("lineEnds.objectLiteralCurly", "after", effectiveEmpty);
  }

  private void curlyOverride(String path, String supportedLeft, String supportedEmpty) {
    if (node(path) == null) return;
    acceptOnly(path + ".leftCurly", supportedLeft);
    acceptOnly(path + ".rightCurly", "both");
    acceptOnly(path + ".emptyCurly", supportedEmpty);
  }

  private void applySameLine() {
    onNewLine("sameLine.ifElse", value -> common.ELSE_ON_NEW_LINE = value);
    onNewLine("sameLine.doWhile", value -> common.WHILE_ON_NEW_LINE = value);
    onNewLine("sameLine.tryCatch", value -> common.CATCH_ON_NEW_LINE = value);
    String elseIf = str("sameLine.elseIf");
    if (elseIf != null) {
      common.SPECIAL_ELSE_IF_TREATMENT = "same".equals(elseIf);
    }
    bodyPlacement("sameLine.ifBody", value -> haxe.IF_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.elseBody", value -> haxe.ELSE_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.forBody", value -> haxe.FOR_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.whileBody", value -> haxe.WHILE_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.doWhileBody", value -> haxe.DO_WHILE_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.tryBody", value -> haxe.TRY_BODY_PLACEMENT = value);
    bodyPlacement("sameLine.catchBody", value -> haxe.CATCH_BODY_PLACEMENT = value);
    // the engine reads the per-construct placements; the common checkbox
    // only mirrors them for the settings UI
    List<Integer> bodyPlacements = List.of(haxe.IF_BODY_PLACEMENT, haxe.ELSE_BODY_PLACEMENT,
                                           haxe.FOR_BODY_PLACEMENT, haxe.WHILE_BODY_PLACEMENT,
                                           haxe.DO_WHILE_BODY_PLACEMENT, haxe.TRY_BODY_PLACEMENT,
                                           haxe.CATCH_BODY_PLACEMENT);
    common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE =
      !bodyPlacements.contains(HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE);
    String functionBody = str("sameLine.functionBody");
    if (functionBody != null) {
      haxe.FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE = "next".equals(functionBody);
    }
    String returnSingle = str("sameLine.returnBodySingleLine");
    if (returnSingle != null) {
      haxe.RETURN_VALUE_ON_SAME_LINE = "same".equals(returnSingle);
    }
    acceptOnly("sameLine.anonFunctionBody", "same");
    acceptOnly("sameLine.expressionIf", "same");
    acceptOnly("sameLine.expressionTry", "same");
    acceptOnly("sameLine.expressionCase", "keep");
    acceptOnly("sameLine.comprehensionFor", "same");
    acceptOnly("sameLine.untypedBody", "same");
    acceptOnly("sameLine.returnBody", "same");
    bodyPlacement("sameLine.caseBody", value -> haxe.CASE_BODY_PLACEMENT = value);
    acceptOnly("sameLine.ifElseSemicolonNextLine", "true");
    acceptOnly("sameLine.expressionIfWithBlocks", "false");
  }

  private void applyWhitespace() {
    spaceBefore("whitespace.ifPolicy", value -> common.SPACE_BEFORE_IF_PARENTHESES = value);
    spaceBefore("whitespace.whilePolicy", value -> common.SPACE_BEFORE_WHILE_PARENTHESES = value);
    spaceBefore("whitespace.doPolicy", value -> common.SPACE_BEFORE_WHILE_PARENTHESES = value);
    spaceBefore("whitespace.forPolicy", value -> common.SPACE_BEFORE_FOR_PARENTHESES = value);
    spaceBefore("whitespace.switchPolicy", value -> common.SPACE_BEFORE_SWITCH_PARENTHESES = value);
    spaceBefore("whitespace.catchPolicy", value -> common.SPACE_BEFORE_CATCH_PARENTHESES = value);
    spaceBefore("whitespace.tryPolicy", value -> common.SPACE_BEFORE_TRY_LBRACE = value);
    String binop = str("whitespace.binopPolicy");
    if (binop != null) {
      boolean around = "around".equals(binop);
      common.SPACE_AROUND_ASSIGNMENT_OPERATORS = around;
      common.SPACE_AROUND_LOGICAL_OPERATORS = around;
      common.SPACE_AROUND_EQUALITY_OPERATORS = around;
      common.SPACE_AROUND_RELATIONAL_OPERATORS = around;
      common.SPACE_AROUND_ADDITIVE_OPERATORS = around;
      common.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = around;
      common.SPACE_AROUND_BITWISE_OPERATORS = around;
      common.SPACE_AROUND_SHIFT_OPERATORS = around;
    }
    String ternary = str("whitespace.ternaryPolicy");
    if (ternary != null) {
      boolean around = "around".equals(ternary);
      common.SPACE_BEFORE_QUEST = around;
      common.SPACE_AFTER_QUEST = around;
      common.SPACE_BEFORE_COLON = around;
      common.SPACE_AFTER_COLON = around;
    }
    String comma = str("whitespace.commaPolicy");
    if (comma != null) {
      common.SPACE_BEFORE_COMMA = SPACE_BEFORE_POLICIES.contains(comma);
      boolean after = SPACE_AFTER_POLICIES.contains(comma);
      common.SPACE_AFTER_COMMA = after;
      common.SPACE_AFTER_COMMA_IN_TYPE_ARGUMENTS = after;
    }
    String typeHint = str("whitespace.typeHintColonPolicy");
    if (typeHint != null) {
      haxe.SPACE_BEFORE_TYPE_REFERENCE_COLON = SPACE_BEFORE_POLICIES.contains(typeHint);
      haxe.SPACE_AFTER_TYPE_REFERENCE_COLON = SPACE_AFTER_POLICIES.contains(typeHint);
    }
    String typeCheck = str("whitespace.typeCheckColonPolicy");
    if (typeCheck != null) {
      haxe.SPACE_AROUND_TYPE_CHECK_COLON = "around".equals(typeCheck);
    }
    String paramOpen = str("whitespace.typeParamOpenPolicy");
    String paramClose = str("whitespace.typeParamClosePolicy");
    if (paramOpen != null || paramClose != null) {
      haxe.SPACE_WITHIN_TYPE_PARAMETERS = "around".equals(paramOpen) || "around".equals(paramClose);
    }
    String extension = str("whitespace.typeExtensionPolicy");
    if (extension != null) {
      haxe.STRUCTURE_EXTENSION_ON_OWN_LINE = "after".equals(extension);
    }
    String addCommentSpace = str("whitespace.addLineCommentSpace");
    if (addCommentSpace != null) {
      haxe.ADD_LINE_COMMENT_SPACE = "true".equals(addCommentSpace);
    }
    String interpolation = str("whitespace.formatStringInterpolation");
    if (interpolation != null && !"true".equals(interpolation)) {
      unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.string.interpolation"));
    }
    String arrow = str("whitespace.arrowFunctionsPolicy");
    if (arrow != null) {
      haxe.SPACE_AROUND_ARROW = "around".equals(arrow);
    }
    String functionTypeArrow = str("whitespace.functionTypeHaxe4Policy");
    if (functionTypeArrow != null) {
      haxe.SPACE_AROUND_FUNCTION_TYPE_ARROW = "around".equals(functionTypeArrow);
    }
    String oldFunctionTypeArrow = str("whitespace.functionTypeHaxe3Policy");
    if (oldFunctionTypeArrow != null) {
      haxe.SPACE_AROUND_OLD_FUNCTION_TYPE_ARROW = "around".equals(oldFunctionTypeArrow);
    }
    acceptOnly("whitespace.dotPolicy", "none");
    acceptOnly("whitespace.colonPolicy", "none");
    acceptOnly("whitespace.caseColonPolicy", "onlyAfter");
    String objectFieldColon = str("whitespace.objectFieldColonPolicy");
    if (objectFieldColon != null) {
      haxe.SPACE_BEFORE_OBJECT_FIELD_COLON = SPACE_BEFORE_POLICIES.contains(objectFieldColon);
      haxe.SPACE_AFTER_OBJECT_FIELD_COLON = SPACE_AFTER_POLICIES.contains(objectFieldColon);
    }
    acceptOnly("whitespace.semicolonPolicy", "onlyAfter");
    acceptOnly("whitespace.intervalPolicy", "none");
    acceptOnly("whitespace.compressSuccessiveParenthesis", "true");
    applyParenConfig();
    applyBracesConfig();
    applyBracketConfig();
  }

  private void applyParenConfig() {
    openClose("whitespace.parenConfig.metadataParens",
              within -> haxe.SPACE_WITHIN_METADATA_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.callParens",
              within -> common.SPACE_WITHIN_METHOD_CALL_PARENTHESES = within,
              before -> common.SPACE_BEFORE_METHOD_CALL_PARENTHESES = before);
    openClose("whitespace.parenConfig.funcParamParens",
              within -> common.SPACE_WITHIN_METHOD_PARENTHESES = within,
              before -> common.SPACE_BEFORE_METHOD_PARENTHESES = before);
    openClose("whitespace.parenConfig.anonFuncParamParens",
              within -> common.SPACE_WITHIN_METHOD_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.expressionParens",
              within -> common.SPACE_WITHIN_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.conditionParens", within -> {
      common.SPACE_WITHIN_IF_PARENTHESES = within;
      common.SPACE_WITHIN_WHILE_PARENTHESES = within;
      common.SPACE_WITHIN_FOR_PARENTHESES = within;
      common.SPACE_WITHIN_SWITCH_PARENTHESES = within;
      common.SPACE_WITHIN_CATCH_PARENTHESES = within;
    }, null);
    openClose("whitespace.parenConfig.ifConditionParens",
              within -> common.SPACE_WITHIN_IF_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.switchConditionParens",
              within -> common.SPACE_WITHIN_SWITCH_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.whileConditionParens",
              within -> common.SPACE_WITHIN_WHILE_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.catchParens",
              within -> common.SPACE_WITHIN_CATCH_PARENTHESES = within, null);
    openClose("whitespace.parenConfig.forLoopParens",
              within -> common.SPACE_WITHIN_FOR_PARENTHESES = within, null);
    String sharpParens = "whitespace.parenConfig.sharpConditionParens";
    if (node(sharpParens) != null) {
      markConsumedSubtree(sharpParens);
      unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.sharp.parens", sharpParens));
    }
  }

  private void applyBracesConfig() {
    openClose("whitespace.bracesConfig.blockBraces", null, before -> {
      common.SPACE_BEFORE_METHOD_LBRACE = before;
      common.SPACE_BEFORE_IF_LBRACE = before;
      common.SPACE_BEFORE_ELSE_LBRACE = before;
      common.SPACE_BEFORE_DO_LBRACE = before;
      common.SPACE_BEFORE_WHILE_LBRACE = before;
      common.SPACE_BEFORE_FOR_LBRACE = before;
      common.SPACE_BEFORE_SWITCH_LBRACE = before;
      common.SPACE_BEFORE_TRY_LBRACE = before;
      common.SPACE_BEFORE_CATCH_LBRACE = before;
    });
    for (String construct : List.of("typedefBraces", "anonTypeBraces", "objectLiteralBraces", "unknownBraces")) {
      String path = "whitespace.bracesConfig." + construct;
      if (node(path) == null) continue;
      acceptOnly(path + ".openingPolicy", "before");
      acceptOnly(path + ".closingPolicy", "onlyAfter");
      acceptOnly(path + ".removeInnerWhenEmpty", "true");
    }
  }

  private void applyBracketConfig() {
    Boolean within = null;
    for (String construct : List.of("accessBrackets", "comprehensionBrackets", "arrayLiteralBrackets",
                                    "mapLiteralBrackets", "unknownBrackets")) {
      String path = "whitespace.bracketConfig." + construct;
      if (node(path) == null) continue;
      String opening = str(path + ".openingPolicy");
      String closing = str(path + ".closingPolicy");
      acceptOnly(path + ".removeInnerWhenEmpty", "true");
      if (opening != null || closing != null) {
        boolean constructWithin = (opening != null && SPACE_AFTER_POLICIES.contains(opening))
                                  || (closing != null && SPACE_BEFORE_POLICIES.contains(closing));
        within = within == null ? constructWithin : within | constructWithin;
      }
    }
    if (within != null) {
      common.SPACE_WITHIN_BRACKETS = within;
    }
  }

  private void applyEmptyLines() {
    Integer maxAnywhere = intVal("emptyLines.maxAnywhereInFile");
    if (maxAnywhere != null) {
      blankLinesClamp = maxAnywhere;
      common.KEEP_BLANK_LINES_IN_CODE = maxAnywhere;
      common.KEEP_BLANK_LINES_IN_DECLARATIONS = maxAnywhere;
    }
    applyInt("emptyLines.afterPackage", value -> common.BLANK_LINES_AFTER_PACKAGE = value);
    applyInt("emptyLines.betweenTypes", value -> common.BLANK_LINES_AROUND_CLASS = value);
    applyInt("emptyLines.betweenSingleLineTypes", value -> haxe.KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES = value);
    applyInt("emptyLines.afterFileHeaderComment", value -> haxe.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER = value);
    applyInt("emptyLines.classEmptyLines.betweenVars", value -> common.BLANK_LINES_AROUND_FIELD = value);
    applyInt("emptyLines.classEmptyLines.betweenFunctions", value -> common.BLANK_LINES_AROUND_METHOD = value);
    applyInt("emptyLines.classEmptyLines.beginType", value -> common.BLANK_LINES_AFTER_CLASS_HEADER = value);
    applyInt("emptyLines.classEmptyLines.endType", value -> common.BLANK_LINES_BEFORE_CLASS_END = value);
    String beforeRCurly = str("emptyLines.beforeRightCurly");
    if (beforeRCurly != null) {
      common.KEEP_BLANK_LINES_BEFORE_RBRACE = "remove".equals(beforeRCurly) ? 0 : common.KEEP_BLANK_LINES_IN_DECLARATIONS;
    }
    String afterLCurly = str("emptyLines.afterLeftCurly");
    if (afterLCurly != null) {
      haxe.KEEP_BLANK_LINES_AFTER_LBRACE = "remove".equals(afterLCurly) ? 0 : common.KEEP_BLANK_LINES_IN_CODE;
    }
    // beforeBlocks' visible effect beyond the brace rules is the blank
    // between a case's ':' and its body
    String beforeBlocks = str("emptyLines.beforeBlocks");
    if (beforeBlocks != null) {
      haxe.KEEP_BLANK_LINES_AFTER_CASE_COLON = "remove".equals(beforeBlocks) ? 0 : common.KEEP_BLANK_LINES_IN_CODE;
    }
    applyInt("emptyLines.importAndUsing.beforeType", value -> {
      common.BLANK_LINES_AFTER_IMPORTS = value;
      haxe.MINIMUM_BLANK_LINES_AFTER_USING = value;
    });
    Integer betweenImports = intVal("emptyLines.importAndUsing.betweenImports");
    String importsLevel = str("emptyLines.importAndUsing.betweenImportsLevel");
    if (betweenImports != null || importsLevel != null) {
      applyImportGrouping(betweenImports == null ? 1 : betweenImports,
                          importsLevel == null ? "all" : importsLevel);
    }
    // the remaining flat-set boundaries the single member-blank model covers
    // at THEIR defaults only
    acceptOnly("emptyLines.classEmptyLines.betweenStaticVars", "0");
    applyInt("emptyLines.classEmptyLines.afterStaticVars", value -> haxe.BLANK_LINES_BETWEEN_FIELD_GROUPS = value);
    applyInt("emptyLines.classEmptyLines.afterPrivateVars", value -> haxe.BLANK_LINES_BETWEEN_FIELD_GROUPS = value);
    acceptOnly("emptyLines.classEmptyLines.afterVars", "1");
    acceptOnly("emptyLines.classEmptyLines.afterStaticFunctions", "1");
    acceptOnly("emptyLines.classEmptyLines.betweenStaticFunctions", "1");
    acceptOnly("emptyLines.classEmptyLines.afterPrivateFunctions", "1");
    acceptOnly("emptyLines.classEmptyLines.existingBetweenFields", "keep");
    for (String kind : List.of("macroClassEmptyLines", "abstractEmptyLines", "externClassEmptyLines",
                               "interfaceEmptyLines", "enumEmptyLines", "typedefEmptyLines",
                               "enumAbstractEmptyLines")) {
      String path = "emptyLines." + kind;
      if (node(path) != null) {
        markConsumedSubtree(path);
        unsupported.add(HaxeCodeStyleBundle.message("hxformat.unsupported.member.blanks", path));
      }
    }
    for (String key : List.of("afterIf", "beforeElse", "afterElse", "beforeEnd", "beforeError", "afterError")) {
      acceptOnly("emptyLines.conditionalsEmptyLines." + key, "0");
    }
    // afterReturn/afterBlocks at their Remove default are covered by the
    // beforeRightCurly cap and the keyword-joining rules
    acceptOnly("emptyLines.afterReturn", "remove");
    acceptOnly("emptyLines.afterBlocks", "remove");
    acceptOnly("emptyLines.finalNewline", "true");
    acceptOnly("emptyLines.beforePackage", "0");
    // "ignore" keeps the written shape, which 0 also does here
    applyCommentPolicy("emptyLines.beforeDocCommentEmptyLines", value -> haxe.BLANK_LINES_BEFORE_FIELD_DOC_COMMENT = value);
    applyCommentPolicy("emptyLines.afterFieldsWithDocComments", value -> haxe.BLANK_LINES_AFTER_DOCUMENTED_FIELD = value);
    acceptOnly("emptyLines.betweenMultilineComments", "0");
    acceptOnly("emptyLines.lineCommentsBetweenTypes", "keep");
    acceptOnly("emptyLines.lineCommentsBetweenFunctions", "keep");
    acceptOnly("emptyLines.importAndUsing.beforeUsing", "1");
  }

  private void applyImportGrouping(int betweenImports, String level) {
    if (betweenImports == 0) {
      haxe.KEEP_BLANK_LINES_BETWEEN_IMPORTS = 0;
      haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS = 0;
      return;
    }
    Integer depth = IMPORT_LEVEL_DEPTHS.get(level);
    if (depth == null) {
      // fullPackage compares the package WITHOUT the class name - the import-order key
      // includes it, so same-package imports would still separate
      unsupported.add("emptyLines.importAndUsing.betweenImportsLevel=" + level);
      return;
    }
    haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS = Math.min(betweenImports, blankLinesClamp);
    haxe.IMPORT_GROUP_PACKAGE_DEPTH = depth;
  }

  /**
   * Reads an OpenClosePolicy object: within = space just inside the pair
   * (opening implies space AFTER '(' , closing implies space BEFORE ')').
   */
  private void openClose(String path, @Nullable Consumer<Boolean> withinSetter, @Nullable Consumer<Boolean> beforeSetter) {
    if (node(path) == null) return;
    String opening = str(path + ".openingPolicy");
    String closing = str(path + ".closingPolicy");
    acceptOnly(path + ".removeInnerWhenEmpty", "true");
    if (withinSetter != null && (opening != null || closing != null)) {
      boolean within = (opening != null && SPACE_AFTER_POLICIES.contains(opening))
                       || (closing != null && SPACE_BEFORE_POLICIES.contains(closing));
      withinSetter.accept(within);
    }
    if (beforeSetter != null && opening != null) {
      beforeSetter.accept(SPACE_BEFORE_POLICIES.contains(opening));
    }
  }

  private void onNewLine(String path, Consumer<Boolean> setter) {
    String value = str(path);
    if (value == null) return;
    if ("keep".equals(value)) {
      unsupported.add(path + "=keep");
      return;
    }
    setter.accept("next".equals(value));
  }

  private void spaceBefore(String path, Consumer<Boolean> setter) {
    String value = str(path);
    if (value == null) return;
    setter.accept(SPACE_AFTER_POLICIES.contains(value));
  }

  /** A doc-comment blank policy (one / none / ignore) into a minimum blank-line count. */
  private void applyCommentPolicy(String path, IntConsumer setter) {
    String value = str(path);
    if (value == null) return;
    setter.accept("one".equals(value) ? 1 : 0);
  }

  /**
   * A chain construct's rules: the thresholds the rule engine reproduces are
   * lifted from matching rule shapes - an onePerLineAfterFirst rule's
   * itemCount / lineLength+anyItemLength conditions, a noWrap rule's
   * totalItemLength guard; other rule shapes have no counterpart.
   */
  private void applyChainRules(String path, ChainSetters setters) {
    if (node(path) == null) return;
    markConsumedSubtree(path);
    JsonNode rules = node(path + ".rules");
    if (rules == null || !rules.isArray()) return;
    for (JsonNode rule : rules) {
      String type = rule.path("type").asText("");
      if ("onePerLineAfterFirst".equals(type)) {
        Integer count = conditionValue(rule, "itemCount >= n");
        if (count != null) {
          setters.itemCount().accept(count);
        }
        Integer line = conditionValue(rule, "lineLength >= n");
        Integer item = conditionValue(rule, "anyItemLength >= n");
        if (line != null && item != null) {
          setters.lineLength().accept(line);
          setters.itemLength().accept(item);
        }
      }
      if ("noWrap".equals(type)) {
        Integer total = conditionValue(rule, "totalItemLength <= n");
        if (total != null) {
          setters.totalLength().accept(total);
        }
      }
    }
  }

  /** The four split thresholds of one operator chain kind. */
  private record ChainSetters(IntConsumer lineLength, IntConsumer itemLength, IntConsumer itemCount, IntConsumer totalLength) {
  }

  @Nullable
  private static Integer conditionValue(@NotNull JsonNode rule, @NotNull String cond) {
    JsonNode conditions = rule.get("conditions");
    if (conditions == null || !conditions.isArray()) return null;
    for (JsonNode condition : conditions) {
      if (cond.equals(condition.path("cond").asText(""))) {
        JsonNode value = condition.get("value");
        return value == null || !value.canConvertToInt() ? null : value.intValue();
      }
    }
    return null;
  }

  /** A sameLine.*Body policy (next / same / keep) into a per-construct body placement. */
  private void bodyPlacement(String path, IntConsumer setter) {
    String value = str(path);
    if (value == null) return;
    switch (value) {
      case "next" -> setter.accept(HaxeCodeStyleSettings.BODY_PLACEMENT_NEXT_LINE);
      case "same" -> setter.accept(HaxeCodeStyleSettings.BODY_PLACEMENT_SAME_LINE);
      case "keep" -> setter.accept(HaxeCodeStyleSettings.BODY_PLACEMENT_KEEP);
      default -> unsupported.add(path + "=" + value);
    }
  }

  /** Ints in the emptyLines section obey the maxAnywhereInFile clamp. */
  private void applyInt(String path, IntConsumer setter) {
    Integer value = intVal(path);
    if (value != null) {
      setter.accept(Math.min(value, blankLinesClamp));
    }
  }

  /** Consumes the key when it holds the only supported value; reports it otherwise. */
  private void acceptOnly(String path, String supportedValue) {
    String value = str(path);
    if (value != null && !supportedValue.equals(value)) {
      unsupported.add(path + "=" + value);
    }
  }

  private void markConsumedSubtree(String path) {
    JsonNode subtree = node(path);
    if (subtree != null) {
      markConsumed(subtree, path);
    }
  }

  private void markConsumed(JsonNode subtree, String path) {
    consumed.add(path);
    for (Map.Entry<String, JsonNode> field : subtree.properties()) {
      markConsumed(field.getValue(), path + "." + field.getKey());
    }
  }

  private void collectLeftovers(JsonNode subtree, String path) {
    if (subtree.isNull()) return;
    if (!subtree.isObject()) {
      if (!consumed.contains(path)) {
        unsupported.add(path);
      }
      return;
    }
    for (Map.Entry<String, JsonNode> field : subtree.properties()) {
      String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
      if (consumed.contains(childPath)) continue;
      collectLeftovers(field.getValue(), childPath);
    }
  }

  @Nullable
  private String str(String path) {
    JsonNode value = node(path);
    if (value == null || value.isObject() || value.isArray()) return null;
    consumed.add(path);
    return value.asText();
  }

  @Nullable
  private Integer intVal(String path) {
    JsonNode value = node(path);
    if (value == null || !value.canConvertToInt()) return null;
    consumed.add(path);
    return value.asInt();
  }

  /** The value at the dotted path; a JSON null (the tool's "unset" for per-construct overrides) counts as absent. */
  @Nullable
  private JsonNode node(String path) {
    JsonNode current = root;
    // the dotted config path's segments
    for (String part : path.split("\\.")) {
      current = current.get(part);
      if (current == null || current.isNull()) return null;
    }
    return current;
  }
}
