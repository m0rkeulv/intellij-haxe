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

    public boolean SPACE_AROUND_ARROW = true;
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

    // reformat also re-aligns the INACTIVE #if/#elseif/#else branches (the
    // block formatter only reaches the active one). Off by default: inactive
    // code is NOT parsed, so its lines are shifted as a group - statements
    // nested inside the branch do not get their own indent steps
    public boolean ALIGN_INACTIVE_CONDITIONAL_BRANCHES = false;
    public boolean FORMAT_INACTIVE_BRANCHES = true;

    // a NAMED function's non-block body (function f() return x;) moves to its
    // own line; anonymous/arrow function bodies always stay inline
    public boolean FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE = false;

    // a hand-broken "return\n value;" is re-joined; off keeps the break
    public boolean RETURN_VALUE_ON_SAME_LINE = false;

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
    // 0 = no grouping (the keep cap above applies); above 0, imports whose
    // first IMPORT_GROUP_PACKAGE_DEPTH package segments differ get exactly
    // this many blank lines between them and same-group imports stay snug
    public int BLANK_LINES_BETWEEN_IMPORT_GROUPS = 0;
    public int IMPORT_GROUP_PACKAGE_DEPTH = 1;

    protected HaxeCodeStyleSettings(CodeStyleSettings container) {
        super("HaxeCodeStyleSettings", container);
    }
}
