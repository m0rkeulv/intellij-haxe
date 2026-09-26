/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.ide.formatter.settings;

import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeCodeStyleSettings extends CustomCodeStyleSettings {
    // a project's own hxformat.json (haxe-formatter config) overrides these
    // settings per file while present, like EditorConfig support does
    public boolean USE_PROJECT_HXFORMAT = true;

    // the three arrow kinds space separately, as haxe-formatter does: arrow
    // functions (x -> x), Haxe 4 function types ((Int) -> Int) and Haxe 3
    // function types (Int -> Int; the hxformat profile leaves those unspaced)
    public boolean SPACE_AROUND_ARROW = true;
    public boolean SPACE_AROUND_FUNCTION_TYPE_ARROW = true;
    public boolean SPACE_AROUND_OLD_FUNCTION_TYPE_ARROW = true;
    public boolean SPACE_BEFORE_TYPE_REFERENCE_COLON = false;
    public boolean SPACE_AFTER_TYPE_REFERENCE_COLON = false;
    public boolean SPACE_WITHIN_TYPE_PARAMETERS = false;
    public boolean SPACE_WITHIN_STRING_INTERPOLATION = false;
    // the (expr : Type) type-check colon is conventionally spaced, UNLIKE type hints
    public boolean SPACE_AROUND_TYPE_CHECK_COLON = true;
    public boolean SPACE_BEFORE_OBJECT_FIELD_COLON = false;
    public boolean SPACE_AFTER_OBJECT_FIELD_COLON = true;
    public boolean SPACE_WITHIN_METADATA_PARENTHESES = false;

    // a structure extension hugs a one-line body ({ > Base, ... }) and takes
    // its own line in a multi-line one; off keeps it as written
    public boolean STRUCTURE_EXTENSION_ON_OWN_LINE = false;
    public boolean FORMAT_DOC_COMMENTS = true;

    // reindent the interior lines of a plain /*..*/ comment the way
    // hxformat does: common margin removed, one level deeper than the
    // comment, star rails aligned. Off restores the IntelliJ convention of
    // leaving comment interiors alone. First-column comments stay fully
    // untouched while KEEP_FIRST_COLUMN_COMMENT pins their opener.
    public boolean REINDENT_MULTILINE_COMMENTS = true;

    // reformat also aligns inactive branches that failed to parse cleanly
    // (parsed branches are block-formatted under FORMAT_INACTIVE_BRANCHES).
    // Off by default: an unparsable blob's lines shift as a group, so
    // statements nested inside it do not get their own indent steps
    public boolean ALIGN_INACTIVE_CONDITIONAL_BRANCHES = false;
    public boolean FORMAT_INACTIVE_BRANCHES = true;

    // a NAMED function's non-block body (function f() return x;) moves to its
    // own line; anonymous/arrow function bodies always stay inline
    public boolean FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE = false;

    // a hand-broken "return\n value;" is re-joined; off keeps the break
    public boolean RETURN_VALUE_ON_SAME_LINE = false;

    // "//text" normalizes to "// text" on reformat (hxformat's
    // whitespace.addLineCommentSpace); divider art and "///" keep their shape
    public boolean ADD_LINE_COMMENT_SPACE = false;

    // where a control statement's NON-BLOCK body goes, per construct
    // (hxformat's sameLine.*Body): NEXT_LINE breaks it onto its own line,
    // SAME_LINE joins it onto the header's line, KEEP leaves it as written.
    // DEFAULT defers to the common "keep control statement in one line"
    // checkbox (checked = KEEP, unchecked = NEXT_LINE)
    public static final int BODY_PLACEMENT_DEFAULT = 0;
    public static final int BODY_PLACEMENT_NEXT_LINE = 1;
    public static final int BODY_PLACEMENT_SAME_LINE = 2;
    public static final int BODY_PLACEMENT_KEEP = 3;
    public int IF_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int ELSE_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int FOR_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int WHILE_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int DO_WHILE_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int TRY_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    public int CATCH_BODY_PLACEMENT = BODY_PLACEMENT_DEFAULT;
    // a case's body (hxformat's sameLine.caseBody) has no common-checkbox
    // equivalent: the plain default keeps the written shape
    public int CASE_BODY_PLACEMENT = BODY_PLACEMENT_KEEP;

    // counts BLANK LINES (like the platform's BLANK_LINES_* options)
    public int MINIMUM_BLANK_LINES_AFTER_USING = 1;
    // gap after a block comment that OPENS the file (a license header);
    // 0 keeps whatever was written
    public int MINIMUM_BLANK_LINES_AFTER_FILE_HEADER = 0;
    // keep cap between ADJACENT one-line type declarations (interface One {});
    // 0 pulls them snug, hxformat-style
    public int KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES = 2;
    // keep cap WITHIN the import/using section; 0 makes it one solid block
    public int KEEP_BLANK_LINES_BETWEEN_IMPORTS = 2;
    // keep cap for a blank line directly after a block's '{' (class bodies
    // use the exact BLANK_LINES_AFTER_CLASS_HEADER count instead); 0 pulls
    // the first statement against the brace, hxformat-style
    public int KEEP_BLANK_LINES_AFTER_LBRACE = 2;
    // keep cap between a case's ':' and its first statement (hxformat's
    // emptyLines.beforeBlocks); blanks BETWEEN cases follow the in-code cap
    public int KEEP_BLANK_LINES_AFTER_CASE_COLON = 2;
    // blank lines where a var block's group changes - staticness or
    // visibility (hxformat's classEmptyLines.afterStaticVars and
    // afterPrivateVars); 0 keeps the plain BLANK_LINES_AROUND_FIELD gap
    public int BLANK_LINES_BETWEEN_FIELD_GROUPS = 0;
    // minimum blank lines before a FIELD's doc comment (hxformat's
    // beforeDocCommentEmptyLines) and after a documented field
    // (afterFieldsWithDocComments); 0 keeps the written shape
    public int BLANK_LINES_BEFORE_FIELD_DOC_COMMENT = 0;
    public int BLANK_LINES_AFTER_DOCUMENTED_FIELD = 0;
    // wrapped operator chains (&&/||, +/-) continue ONE step from the line
    // the chain starts on, the way haxe-formatter indents its wraps; off
    // keeps the classic alignment-driven continuation behavior
    public boolean INDENT_WRAPPED_OPERATOR_CHAINS = false;
    // and/or chains split one operand per line, operators leading, when
    // the joined line reaches SPLIT_LINE_LENGTH holding an operand of
    // SPLIT_ITEM_LENGTH, or when SPLIT_ITEM_COUNT operands total more than
    // SPLIT_TOTAL_LENGTH (hxformat's wrapping.opBoolChain conditions,
    // inclusive like the tool's); 0 disables a trigger
    public int BOOL_CHAIN_SPLIT_LINE_LENGTH = 0;
    public int BOOL_CHAIN_SPLIT_ITEM_LENGTH = 0;
    public int BOOL_CHAIN_SPLIT_ITEM_COUNT = 0;
    public int BOOL_CHAIN_SPLIT_TOTAL_LENGTH = 0;
    // +/- chains follow hxformat's wrapping.opAddSubChain rules (see
    // HaxeAdditiveChainRules): a joined line past SPLIT_LINE_LENGTH breaks
    // before every operator when an operand reaches SPLIT_ITEM_LENGTH, else
    // only where it overflows; a fitting line breaks before every operator
    // once SPLIT_ITEM_COUNT operands total more than SPLIT_TOTAL_LENGTH.
    // 0 line length keeps the written shape
    public int ADD_CHAIN_SPLIT_LINE_LENGTH = 0;
    public int ADD_CHAIN_SPLIT_ITEM_LENGTH = 0;
    public int ADD_CHAIN_SPLIT_ITEM_COUNT = 0;
    public int ADD_CHAIN_SPLIT_TOTAL_LENGTH = 0;
    // call arguments fill the JOINED line the way hxformat's callParameter
    // fillLine does: an argument reaching the margin on that line starts a
    // new line even where the other breaks would have made it fit; off
    // leaves the margin wrap to the layout as formatted
    public boolean FILL_CALL_ARGUMENTS_ON_JOINED_LINE = false;
    // a multi-var declaration whose JOINED line would pass this many columns
    // splits one declarator per line (hxformat's wrapping.multiVar
    // lineLengthLargerThan rule); 0 keeps the written shape. A declarator at
    // or under FILL_ITEM_LENGTH keeps the list filling instead (the tool's
    // preceding anyItemLength rule)
    public int MULTI_VAR_SPLIT_WIDTH = 0;
    public int MULTI_VAR_FILL_ITEM_LENGTH = 0;
    // 0 = no grouping (the keep cap above applies); above 0, imports whose
    // first IMPORT_GROUP_PACKAGE_DEPTH package segments differ get exactly
    // this many blank lines between them and same-group imports stay snug
    public int BLANK_LINES_BETWEEN_IMPORT_GROUPS = 0;
    public int IMPORT_GROUP_PACKAGE_DEPTH = 1;

    protected HaxeCodeStyleSettings(CodeStyleSettings container) {
        super("HaxeCodeStyleSettings", container);
    }
}
