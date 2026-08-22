package com.intellij.plugins.haxe.ide.formatter.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Maps haxe-formatter (HaxeCheckstyle) configuration onto our code style
 * settings. {@link #applyDefaults} is the settings image of a DEFAULT
 * hxformat.json (verified byte-for-byte by HaxeFormatterComparisonTest);
 * {@link #applyJson} lays a config file's overrides on top and reports the
 * keys it could not honor.
 */
public final class HxformatCodeStyle {

  private HxformatCodeStyle() {
  }

  /** Our settings equivalent of a DEFAULT hxformat.json (haxe-formatter 1.18). */
  public static void applyDefaults(@NotNull CodeStyleSettings settings) {
    resetWrapFields(settings);
    CodeStyleSettings.IndentOptions indent = settings.getIndentOptions(HaxeFileType.INSTANCE);
    // indentation.character="tab", tabWidth=4
    indent.USE_TAB_CHARACTER = true;
    indent.TAB_SIZE = 4;
    indent.INDENT_SIZE = 4;
    // a wrapped declaration header (implementsExtends) continues TWO steps in
    indent.CONTINUATION_INDENT_SIZE = 8;

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
    common.SPACE_WITHIN_PARENTHESES = false;
    common.SPACE_WITHIN_BRACKETS = false;
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

    // sameLine.ifBody/elseBody/forBody/whileBody=Next (non-block bodies break)
    common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE = false;
    // lineEnds.emptyCurly=NoBreak ({} collapses)
    common.KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = true;
    common.KEEP_SIMPLE_METHODS_IN_ONE_LINE = true;
    common.KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE = true;
    // wrapping.arrayWrap/mapWrap/objectLiteral/methodChain: break one-per-line
    // when the line exceeds maxLineLength. The settings UI stores "chop down
    // if long" as WRAP_ON_EVERY_ITEM | WRAP_AS_NEEDED - a bare
    // WRAP_ON_EVERY_ITEM renders as "invalid option value" in the combo box
    common.ARRAY_INITIALIZER_WRAP = UI_CHOP_DOWN;
    common.METHOD_CALL_CHAIN_WRAP = UI_CHOP_DOWN;
    // wrapping.implementsExtends: FillLine - break only past maxLineLength
    common.EXTENDS_LIST_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;

    HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
    // whitespace.arrowFunctionsPolicy/functionTypeHaxe4Policy=Around
    haxe.SPACE_AROUND_ARROW = true;
    // whitespace.typeHintColonPolicy=None
    haxe.SPACE_BEFORE_TYPE_REFERENCE_COLON = false;
    haxe.SPACE_AFTER_TYPE_REFERENCE_COLON = false;
    // whitespace.typeParamOpenPolicy/typeParamClosePolicy=None
    haxe.SPACE_WITHIN_TYPE_PARAMETERS = false;
    // whitespace.typeCheckColonPolicy=Around
    haxe.SPACE_AROUND_TYPE_CHECK_COLON = true;
    // whitespace.parenConfig.metadataParens=NoSpace
    haxe.SPACE_WITHIN_METADATA_PARENTHESES = false;
    // whitespace.formatStringInterpolation=true
    haxe.SPACE_WITHIN_STRING_INTERPOLATION = false;
    // typeExtensionPolicy=After
    haxe.STRUCTURE_EXTENSION_ON_OWN_LINE = true;
    // indentation.conditionalPolicy=Aligned - inactive branches too
    haxe.ALIGN_INACTIVE_CONDITIONAL_BRANCHES = true;
    // sameLine.functionBody=Next (anonFunctionBody=Same has no flag - always inline)
    haxe.FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE = true;
    // sameLine.returnBodySingleLine - a broken return re-joins its value
    haxe.RETURN_VALUE_ON_SAME_LINE = true;
    // emptyLines.importAndUsing.beforeType=1
    haxe.MINIMUM_BLANK_LINES_AFTER_USING = 1;
    // emptyLines.betweenSingleLineTypes=0
    haxe.KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES = 0;
    // emptyLines.importAndUsing.betweenImports=0
    haxe.KEEP_BLANK_LINES_BETWEEN_IMPORTS = 0;
    // emptyLines.afterFileHeaderComment=1
    haxe.MINIMUM_BLANK_LINES_AFTER_FILE_HEADER = 1;
    haxe.BLANK_LINES_BETWEEN_IMPORT_GROUPS = 0;
    haxe.IMPORT_GROUP_PACKAGE_DEPTH = 1;
  }

  /** The settings UI's encoding of "chop down if long". */
  private static final int UI_CHOP_DOWN =
    CommonCodeStyleSettings.WRAP_ON_EVERY_ITEM | CommonCodeStyleSettings.WRAP_AS_NEEDED;

  /**
   * A scheme created by the settings UI CLONES the currently selected scheme,
   * so unmapped wrap fields would inherit arbitrary (possibly corrupted)
   * values - the hxformat image must not depend on what was selected. Also
   * repairs a legacy bare WRAP_ON_EVERY_ITEM, which the settings combos
   * reject ("chop down if long" is stored as EVERY_ITEM|AS_NEEDED).
   */
  private static void resetWrapFields(CodeStyleSettings settings) {
    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    for (Field field : CommonCodeStyleSettings.class.getFields()) {
      if (field.getType() != int.class || !field.getName().endsWith("_WRAP")) continue;
      try {
        field.setInt(common, CommonCodeStyleSettings.DO_NOT_WRAP);
      }
      catch (IllegalAccessException ignored) {
      }
    }
  }

  /**
   * Applies an hxformat.json's overrides on top of {@link #applyDefaults}.
   * Returns the config paths present in the file that we could not honor.
   */
  public static List<String> applyJson(@NotNull CodeStyleSettings settings, @NotNull JsonNode root) {
    Applier applier = new Applier(settings, root);
    applier.apply();
    return applier.unsupported();
  }

  /** Walks the known key paths, consuming what it maps; the rest is reported. */
  private static final class Applier {
    // WhitespacePolicy values that put a space AFTER the token / BEFORE it
    private static final Set<String> SPACE_AFTER_POLICIES = Set.of("after", "onlyAfter", "around");
    private static final Set<String> SPACE_BEFORE_POLICIES = Set.of("before", "onlyBefore", "around");
    /** betweenImportsLevel value -> our grouping depth; "all" separates every import, which a full-path key reproduces. */
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

    Applier(CodeStyleSettings settings, JsonNode root) {
      this.settings = settings;
      this.common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
      this.haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
      this.root = root;
    }

    void apply() {
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

    List<String> unsupported() {
      return unsupported;
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
      // we never column-align array matrices
      acceptOnly("wrapping.arrayMatrixWrap", "noMatrixWrap");
      wrapConstruct("wrapping.arrayWrap", value -> common.ARRAY_INITIALIZER_WRAP = value);
      wrapConstruct("wrapping.mapWrap", value -> common.ARRAY_INITIALIZER_WRAP = value);
      wrapConstruct("wrapping.objectLiteral", value -> common.ARRAY_INITIALIZER_WRAP = value);
      wrapConstruct("wrapping.methodChain", value -> common.METHOD_CALL_CHAIN_WRAP = value);
      wrapConstruct("wrapping.implementsExtends", value -> common.EXTENDS_LIST_WRAP = value);
      wrapConstruct("wrapping.functionSignature", value -> common.METHOD_PARAMETERS_WRAP = value);
      wrapConstruct("wrapping.anonFunctionSignature", value -> common.METHOD_PARAMETERS_WRAP = value);
      wrapConstruct("wrapping.callParameter", value -> common.CALL_PARAMETERS_WRAP = value);
      wrapConstruct("wrapping.opBoolChain", value -> common.BINARY_OPERATION_WRAP = value);
      wrapConstruct("wrapping.opAddSubChain", value -> common.BINARY_OPERATION_WRAP = value);
      for (String construct : List.of("typeParameter", "metadataCallParameter", "multiVar", "casePattern", "anonType")) {
        String path = "wrapping." + construct;
        if (node(path) != null) {
          markConsumedSubtree(path);
          unsupported.add(path + " (no wrap target on our side)");
        }
      }
    }

    /**
     * Their wrap engine picks the FIRST rule whose conditions all hold; ours
     * is one policy per construct. The exceedsMaxLineLength rule is the
     * margin behavior, so its type approximates the intent best; without one
     * the first rule, then defaultWrap, decides.
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
          if (hasMarginCondition(rule)) {
            type = ruleType.asText();
            break;
          }
        }
        unsupported.add(path + ".rules (rule engine approximated by one policy)");
      }
      if (type == null && construct.get("defaultWrap") != null) {
        type = construct.get("defaultWrap").asText();
      }
      if (type == null) return;
      setter.accept(switch (type) {
        case "onePerLine", "onePerLineAfterFirst", "equalNumber" -> UI_CHOP_DOWN;
        case "fillLine", "fillLineWithLeadingBreak" -> CommonCodeStyleSettings.WRAP_AS_NEEDED;
        default -> CommonCodeStyleSettings.DO_NOT_WRAP; // noWrap, keep
      });
    }

    private static boolean hasMarginCondition(JsonNode rule) {
      JsonNode conditions = rule.get("conditions");
      if (conditions == null || !conditions.isArray()) return false;
      for (JsonNode condition : conditions) {
        JsonNode kind = condition.get("cond");
        if (kind != null && "exceedsMaxLineLength".equals(kind.asText())) return true;
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
      // After-style on our side, whatever BRACE_STYLE says)
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
      // one flag covers every statement body; Next on any of them breaks all
      boolean anyBodyNext = false;
      boolean anyBodyKeep = false;
      for (String key : List.of("sameLine.ifBody", "sameLine.elseBody", "sameLine.forBody",
                                "sameLine.whileBody", "sameLine.doWhileBody",
                                "sameLine.tryBody", "sameLine.catchBody")) {
        String value = str(key);
        if ("next".equals(value)) anyBodyNext = true;
        if ("keep".equals(value) || "same".equals(value)) anyBodyKeep = true;
      }
      if (anyBodyNext && anyBodyKeep) {
        unsupported.add("sameLine.*Body (mixed values; using one policy for all bodies)");
      }
      if (anyBodyNext || anyBodyKeep) {
        common.KEEP_CONTROL_STATEMENT_IN_ONE_LINE = !anyBodyNext;
      }
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
      acceptOnly("sameLine.caseBody", "next");
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
      String interpolation = str("whitespace.formatStringInterpolation");
      if (interpolation != null && !"true".equals(interpolation)) {
        unsupported.add("whitespace.formatStringInterpolation=false (interpolations always format)");
      }
      String arrow = str("whitespace.arrowFunctionsPolicy");
      if (arrow != null) {
        haxe.SPACE_AROUND_ARROW = "around".equals(arrow);
      }
      acceptOnly("whitespace.functionTypeHaxe4Policy", "around");
      acceptOnly("whitespace.functionTypeHaxe3Policy", "none");
      acceptOnly("whitespace.dotPolicy", "none");
      acceptOnly("whitespace.colonPolicy", "none");
      acceptOnly("whitespace.caseColonPolicy", "onlyAfter");
      acceptOnly("whitespace.objectFieldColonPolicy", "after");
      acceptOnly("whitespace.semicolonPolicy", "onlyAfter");
      acceptOnly("whitespace.intervalPolicy", "none");
      acceptOnly("whitespace.compressSuccessiveParenthesis", "true");
      acceptOnly("whitespace.addLineCommentSpace", "true");
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
        unsupported.add(sharpParens + " (#if condition spacing is PSI-deferred)");
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
      // the remaining flat-set boundaries our single member-blank model covers
      // at THEIR defaults only
      acceptOnly("emptyLines.classEmptyLines.betweenStaticVars", "0");
      acceptOnly("emptyLines.classEmptyLines.afterStaticVars", "1");
      acceptOnly("emptyLines.classEmptyLines.afterPrivateVars", "1");
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
          unsupported.add(path + " (one shared member-blank set for all type kinds)");
        }
      }
      for (String key : List.of("afterIf", "beforeElse", "afterElse", "beforeEnd", "beforeError", "afterError")) {
        acceptOnly("emptyLines.conditionalsEmptyLines." + key, "0");
      }
      // we KEEP blanks in code bodies; their default actively removes these
      acceptOnly("emptyLines.afterReturn", "keep");
      acceptOnly("emptyLines.beforeBlocks", "keep");
      acceptOnly("emptyLines.afterBlocks", "keep");
      acceptOnly("emptyLines.finalNewline", "true");
      acceptOnly("emptyLines.beforePackage", "0");
      acceptOnly("emptyLines.afterLeftCurly", "remove");
      acceptOnly("emptyLines.beforeDocCommentEmptyLines", "one");
      acceptOnly("emptyLines.afterFieldsWithDocComments", "one");
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
        // fullPackage compares the package WITHOUT the class name - our key
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

    /** Ints in the emptyLines section obey the maxAnywhereInFile clamp. */
    private void applyInt(String path, IntConsumer setter) {
      Integer value = intVal(path);
      if (value != null) {
        setter.accept(Math.min(value, blankLinesClamp));
      }
    }

    /** Consumes the key when it holds the only value we support; reports it otherwise. */
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
      Iterator<Map.Entry<String, JsonNode>> fields = subtree.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        markConsumed(field.getValue(), path + "." + field.getKey());
      }
    }

    private void collectLeftovers(JsonNode subtree, String path) {
      if (!subtree.isObject()) {
        if (!consumed.contains(path)) {
          unsupported.add(path);
        }
        return;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = subtree.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
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

    @Nullable
    private JsonNode node(String path) {
      JsonNode current = root;
      for (String part : path.split("\\.")) {
        current = current.get(part);
        if (current == null) return null;
      }
      return current;
    }
  }
}
