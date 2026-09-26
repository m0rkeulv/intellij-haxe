package com.intellij.plugins.haxe.ide.formatter.settings;

/**
 * haxe-formatter's built-in defaults (formatter 1.18.0, its
 * {@code src/formatter/config/*.hx} {@code @:default} values): the profile
 * {@link HxformatCodeStyle#applyDefaults} installs, and the baseline an
 * imported hxformat.json overrides. They apply only under that profile; the
 * plain scheme keeps the plugin's own defaults in {@link HaxeCodeStyleSettings}.
 * <p>
 * The tool prints its complete default configuration with
 * {@code haxelib run formatter --default-config <file>} (the file must exist
 * beforehand); {@code testData/formatter/comparison/default-hxformat.json} is
 * that output and {@code HaxeFormatterDefaultsTest} checks these values against it.
 */
public final class HaxeFormatterDefaults {

  // indentation: character="tab", tabWidth=4; a wrapped declaration header continues two steps in
  public static final int TAB_WIDTH = 4;
  public static final int CONTINUATION_STEPS = 2;

  // wrapping.maxLineLength
  public static final int MAX_LINE_LENGTH = 160;

  // wrapping.opBoolChain rules: lineLength >= 140 (+ anyItemLength >= 40 -> one per line, else fill);
  // itemCount >= 4 -> one per line unless totalItemLength <= 120; a chain of up to 3 operands on a
  // fitting line is left alone
  public static final int BOOL_CHAIN_LINE_LENGTH = 140;
  public static final int BOOL_CHAIN_ITEM_LENGTH = 40;
  public static final int BOOL_CHAIN_ITEM_COUNT = 4;
  public static final int BOOL_CHAIN_TOTAL_LENGTH = 120;

  // wrapping.opAddSubChain rules, the same shape
  public static final int ADD_CHAIN_LINE_LENGTH = 160;
  public static final int ADD_CHAIN_ITEM_LENGTH = 60;
  public static final int ADD_CHAIN_ITEM_COUNT = 4;
  public static final int ADD_CHAIN_TOTAL_LENGTH = 120;

  // the noWrap rule both chain kinds share: itemCount <= 3 on a line within the margin
  public static final int CHAIN_KEEP_ITEM_COUNT = 3;

  // wrapping.multiVar rules: lineLength >= 80 -> one per line, unless anyItemLength <= 15 -> fill
  public static final int MULTI_VAR_LINE_LENGTH = 80;
  public static final int MULTI_VAR_FILL_ITEM_LENGTH = 15;

  // emptyLines: maxAnywhereInFile, afterPackage, importAndUsing.beforeType, betweenTypes,
  // betweenSingleLineTypes, importAndUsing.betweenImports, afterFileHeaderComment
  public static final int MAX_BLANK_LINES = 1;
  public static final int BLANK_LINES_AFTER_PACKAGE = 1;
  public static final int BLANK_LINES_AFTER_IMPORTS = 1;
  public static final int BLANK_LINES_BETWEEN_TYPES = 1;
  public static final int BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES = 0;
  public static final int BLANK_LINES_BETWEEN_IMPORTS = 0;
  public static final int BLANK_LINES_AFTER_FILE_HEADER = 1;

  // emptyLines.classEmptyLines: beginType, endType, betweenVars, betweenFunctions,
  // afterStaticVars/afterPrivateVars; beforeDocCommentEmptyLines/afterFieldsWithDocComments=One
  public static final int BLANK_LINES_BEGIN_TYPE = 0;
  public static final int BLANK_LINES_END_TYPE = 0;
  public static final int BLANK_LINES_BETWEEN_VARS = 0;
  public static final int BLANK_LINES_BETWEEN_FUNCTIONS = 1;
  public static final int BLANK_LINES_BETWEEN_VAR_GROUPS = 1;
  public static final int BLANK_LINES_AROUND_DOCUMENTED_FIELD = 1;

  // emptyLines.afterLeftCurly / beforeRightCurly / beforeBlocks = Remove
  public static final int BLANK_LINES_AT_BLOCK_EDGES = 0;

  private HaxeFormatterDefaults() {
  }
}
