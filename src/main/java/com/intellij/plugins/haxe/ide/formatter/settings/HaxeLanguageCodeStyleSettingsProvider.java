/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2020 Eric Bishton
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

import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.psi.codeStyle.*;
import org.jetbrains.annotations.NotNull;

import static com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable.WrappingOrBraceOption.*;
import static com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable.BlankLinesOption.*;
import static com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable.SpacingOption.*;
import static com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable.OptionAnchor;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

  @NotNull
  @Override
  public Language getLanguage() {
    return HaxeLanguage.INSTANCE;
  }

  @Override
  public String getCodeSample(@NotNull SettingsType settingsType) {
    if (settingsType == SettingsType.SPACING_SETTINGS) {
      return SPACING_CODE_SAMPLE;
    }
    if (settingsType == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
      return WRAPPING_CODE_SAMPLE;
    }
    return BLANK_LINES_CODE_SAMPLE;
  }

  @Override
  public DocCommentSettings getDocCommentSettings(@NotNull CodeStyleSettings rootSettings) {
    return new DocCommentSettings() {
      private final JavaCodeStyleSettings mySettings = rootSettings.getCustomSettings(JavaCodeStyleSettings.class);


      @Override
      public boolean isDocFormattingEnabled() {
        return mySettings.ENABLE_JAVADOC_FORMATTING;
      }

      @Override
      public void setDocFormattingEnabled(boolean formattingEnabled) {
        mySettings.ENABLE_JAVADOC_FORMATTING = formattingEnabled;
      }


      @Override
      public boolean isLeadingAsteriskEnabled() {
        return false; // haxe docs are markdown and we do not want it prefixed with Asterisk
      }

      @Override
      public boolean isRemoveEmptyTags() {
        return mySettings.JD_KEEP_EMPTY_EXCEPTION || mySettings.JD_KEEP_EMPTY_PARAMETER || mySettings.JD_KEEP_EMPTY_RETURN;
      }

      @Override
      public void setRemoveEmptyTags(boolean removeEmptyTags) {
        mySettings.JD_KEEP_EMPTY_RETURN = !removeEmptyTags;
        mySettings.JD_KEEP_EMPTY_PARAMETER = !removeEmptyTags;
        mySettings.JD_KEEP_EMPTY_EXCEPTION = !removeEmptyTags;
      }
    };
  }
  @Override
  public void customizeSettings(@NotNull CodeStyleSettingsCustomizable consumer, @NotNull SettingsType settingsType) {
    if (settingsType == SettingsType.SPACING_SETTINGS) {
      consumer.showStandardOptions(SPACE_BEFORE_METHOD_CALL_PARENTHESES.name(),
                                   SPACE_BEFORE_METHOD_PARENTHESES.name(),
                                   SPACE_BEFORE_IF_PARENTHESES.name(),
                                   SPACE_BEFORE_WHILE_PARENTHESES.name(),
                                   SPACE_BEFORE_FOR_PARENTHESES.name(),
                                   SPACE_BEFORE_CATCH_PARENTHESES.name(),
                                   SPACE_BEFORE_SWITCH_PARENTHESES.name(),
                                   SPACE_AROUND_ASSIGNMENT_OPERATORS.name(),
                                   SPACE_AROUND_LOGICAL_OPERATORS.name(),
                                   SPACE_AROUND_EQUALITY_OPERATORS.name(),
                                   SPACE_AROUND_RELATIONAL_OPERATORS.name(),
                                   SPACE_AROUND_ADDITIVE_OPERATORS.name(),
                                   SPACE_AROUND_MULTIPLICATIVE_OPERATORS.name(),
                                   SPACE_AROUND_BITWISE_OPERATORS.name(),
                                   SPACE_AROUND_SHIFT_OPERATORS.name(),
                                   SPACE_BEFORE_METHOD_LBRACE.name(),
                                   SPACE_BEFORE_IF_LBRACE.name(),
                                   SPACE_BEFORE_ELSE_LBRACE.name(),
                                   SPACE_BEFORE_DO_LBRACE.name(),
                                   SPACE_BEFORE_WHILE_LBRACE.name(),
                                   SPACE_BEFORE_FOR_LBRACE.name(),
                                   SPACE_BEFORE_SWITCH_LBRACE.name(),
                                   SPACE_BEFORE_TRY_LBRACE.name(),
                                   SPACE_BEFORE_CATCH_LBRACE.name(),
                                   SPACE_BEFORE_WHILE_KEYWORD.name(),
                                   SPACE_BEFORE_ELSE_KEYWORD.name(),
                                   SPACE_BEFORE_CATCH_KEYWORD.name(),
                                   SPACE_WITHIN_METHOD_CALL_PARENTHESES.name(),
                                   SPACE_WITHIN_METHOD_PARENTHESES.name(),
                                   SPACE_WITHIN_IF_PARENTHESES.name(),
                                   SPACE_WITHIN_WHILE_PARENTHESES.name(),
                                   SPACE_WITHIN_FOR_PARENTHESES.name(),
                                   SPACE_WITHIN_CATCH_PARENTHESES.name(),
                                   SPACE_WITHIN_SWITCH_PARENTHESES.name(),
                                   SPACE_WITHIN_PARENTHESES.name(),
                                   SPACE_BEFORE_QUEST.name(),
                                   SPACE_AFTER_QUEST.name(),
                                   SPACE_BEFORE_COLON.name(),
                                   SPACE_AFTER_COLON.name(),
                                   SPACE_AFTER_COMMA.name(),
                                   SPACE_AFTER_COMMA_IN_TYPE_ARGUMENTS.name(),
                                   SPACE_BEFORE_COMMA.name(),
                                   SPACE_AROUND_UNARY_OPERATOR.name(),
                                   SPACE_WITHIN_BRACKETS.name()
      );
      // placements and names mirror Java/Kotlin/Groovy (see
      // doc/haxe-formatter-settings-structure.md): arrow spacing sits with
      // the operators, colon options use Kotlin's phrasing
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_AROUND_ARROW", "Arrow functions and function types (->)",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_AROUND_OPERATORS, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_BEFORE_TYPE_REFERENCE_COLON", "Before colon, after declaration name",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_AFTER_TYPE_REFERENCE_COLON", "After colon, before declaration type",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_WITHIN_TYPE_PARAMETERS", "Angle brackets",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_WITHIN, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_WITHIN_STRING_INTERPOLATION", "String interpolation '${' braces",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_WITHIN, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_AROUND_TYPE_CHECK_COLON", "Around type check colon '(value : Type)'",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_WITHIN_METADATA_PARENTHESES", "Metadata parentheses",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_WITHIN, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_BEFORE_OBJECT_FIELD_COLON", "Before object field colon",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "SPACE_AFTER_OBJECT_FIELD_COLON", "After object field colon",
                                CodeStyleSettingsCustomizableOptions.getInstance().SPACES_OTHER, OptionAnchor.NONE);
    }
    else if (settingsType == SettingsType.BLANK_LINES_SETTINGS) {
      consumer.showStandardOptions(
        KEEP_BLANK_LINES_IN_CODE.name(),
        KEEP_BLANK_LINES_IN_DECLARATIONS.name(),
        KEEP_BLANK_LINES_BEFORE_RBRACE.name(),
        BLANK_LINES_AFTER_PACKAGE.name(),
        BLANK_LINES_AFTER_IMPORTS.name(),
        BLANK_LINES_AROUND_CLASS.name(),
        BLANK_LINES_AFTER_CLASS_HEADER.name(),
        BLANK_LINES_AROUND_FIELD.name(),
        BLANK_LINES_AROUND_METHOD.name(),
        BLANK_LINES_BEFORE_CLASS_END.name()
      );
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "MINIMUM_BLANK_LINES_AFTER_USING", "After using:",
                                CodeStyleSettingsCustomizableOptions.getInstance().BLANK_LINES, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "MINIMUM_BLANK_LINES_AFTER_FILE_HEADER", "After file header comment:",
                                CodeStyleSettingsCustomizableOptions.getInstance().BLANK_LINES, OptionAnchor.NONE);
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "KEEP_BLANK_LINES_BETWEEN_SINGLE_LINE_TYPES",
                                "Between single-line types:",
                                CodeStyleSettingsCustomizableOptions.getInstance().BLANK_LINES_KEEP, OptionAnchor.NONE);
    }
    else if (settingsType == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
      consumer.showStandardOptions(
                      RIGHT_MARGIN.name(),
                                   WRAP_ON_TYPING.name(),
                                   KEEP_LINE_BREAKS.name(),
                                   KEEP_FIRST_COLUMN_COMMENT.name(),
                                   KEEP_CONTROL_STATEMENT_IN_ONE_LINE.name(),
                                   KEEP_SIMPLE_BLOCKS_IN_ONE_LINE.name(),
                                   KEEP_SIMPLE_METHODS_IN_ONE_LINE.name(),
                                   KEEP_SIMPLE_LAMBDAS_IN_ONE_LINE.name(),
                                   ARRAY_INITIALIZER_WRAP.name(),
                                   METHOD_CALL_CHAIN_WRAP.name(),
                                   EXTENDS_LIST_WRAP.name(),
                                   BRACE_STYLE.name(),
                                   METHOD_BRACE_STYLE.name(),
                                   CALL_PARAMETERS_WRAP.name(),
                                   CALL_PARAMETERS_LPAREN_ON_NEXT_LINE.name(),
                                   CALL_PARAMETERS_RPAREN_ON_NEXT_LINE.name(),
                                   METHOD_PARAMETERS_WRAP.name(),
                                   METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE.name(),
                                   METHOD_PARAMETERS_RPAREN_ON_NEXT_LINE.name(),
                                   ELSE_ON_NEW_LINE.name(),
                                   WHILE_ON_NEW_LINE.name(),
                                   CATCH_ON_NEW_LINE.name(),
                                   ALIGN_MULTILINE_PARAMETERS.name(),
                                   ALIGN_MULTILINE_PARAMETERS_IN_CALLS.name(),
                                   ALIGN_MULTILINE_BINARY_OPERATION.name(),
                                   BINARY_OPERATION_WRAP.name(),
                                   BINARY_OPERATION_SIGN_ON_NEXT_LINE.name(),
                                   TERNARY_OPERATION_WRAP.name(),
                                   TERNARY_OPERATION_SIGNS_ON_NEXT_LINE.name(),
                                   PARENTHESES_EXPRESSION_LPAREN_WRAP.name(),
                                   PARENTHESES_EXPRESSION_RPAREN_WRAP.name(),
                                   ALIGN_MULTILINE_TERNARY_OPERATION.name(),
                                   SPECIAL_ELSE_IF_TREATMENT.name(),
                                   ASSIGNMENT_WRAP.name(),
                                   PLACE_ASSIGNMENT_SIGN_ON_NEXT_LINE.name()
      );
      // the platform default label says "permits" - Java sealed-class syntax
      // that does not exist in Haxe
      consumer.renameStandardOption(EXTENDS_LIST_WRAP.name(), "Extends/implements list");
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "FUNCTION_EXPRESSION_BODY_ON_NEXT_LINE",
                                "Place body on next line", "Expression body functions");
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "STRUCTURE_EXTENSION_ON_OWN_LINE",
                                "Structure extension '> Base' on own line", "Anonymous structures");
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "RETURN_VALUE_ON_SAME_LINE",
                                "Value on same line as 'return'", "'return' statement");
      // inactive-branch treatment lives in the dedicated Conditional
      // Compilation tab - its options span indentation, spacing and line
      // breaks at once, not just wrapping
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "FORMAT_DOC_COMMENTS",
                                "Indent documentation comment lines", "Comments");
      consumer.showCustomOption(HaxeCodeStyleSettings.class, "REINDENT_MULTILINE_COMMENTS",
                                "Reindent multi-line comment content (hxformat style)", "Comments");
    }
  }

  @Override
  public IndentOptionsEditor getIndentOptionsEditor() {
    return new IndentOptionsEditor(this);
  }
  @org.intellij.lang.annotations.Language("Haxe")
  public static final String SPACING_CODE_SAMPLE = """
    @author("Penelope")
    @:keep
    class Foo<T> {
         var items:Array<T>;
         var lookup:Map<Int, String>;

         function compute(a:Int, b:Int):Int {
              var apply:Int -> Int = v -> v * 2;
              var flag = !(a == b) && a <= b || a != 0;
              var bits = (a & 3) ^ (b | 1) << 2 >> 1;
              var sum = a + b * 2 - b % 3;
              var pick = flag ? apply(sum) : -sum;
              var name = 'value ${pick} of ${sum + 1}';
              var head = items[0];
              var asInt = (head : Int);
              for (i in 0...3) {
                   sum += i;
              }
              while (sum > 9) {
                   sum -= 2;
              }
              do {
                   sum++;
              } while (sum < 5);
              try {
                   check(sum, name);
              } catch (e:String) {
                   sum = 0;
              }
              switch (sum) {
                   case 0:
                        sum = 1;
                   default:
                        sum = 2;
              }
              if (sum > 1) {
                   sum--;
              } else {
                   sum++;
              }
              return sum;
         }

         function check(v:Int, label:String) {}
    }
    """;
  @org.intellij.lang.annotations.Language("Haxe")
  public static final String WRAPPING_CODE_SAMPLE = """
    // a comment kept at the first column
    class Foo extends BaseComponent implements Drawable implements Resizable implements Serializable implements Comparable implements Observable {
         function fLong(argumentAlpha:Int, argumentBravo:Int, argumentCharlie:Int, argumentDelta:Int, argumentEcho:Int, argumentFoxtrot:Int):Int {
              var planets = ['mercury', 'venus', 'earth', 'mars', 'jupiter', 'saturn', 'uranus', 'neptune', 'ceres', 'pluto', 'haumea', 'makemake'];
              var shouted = planets.filter(word -> word.length > 4).map(word -> word.toUpperCase()).join(', ') + planets.join('; ') + 'end';
              var total = argumentAlpha + argumentBravo + argumentCharlie + argumentDelta + argumentEcho + argumentFoxtrot + planets.length;
              total = argumentAlpha * argumentBravo + argumentCharlie * argumentDelta + argumentEcho * argumentFoxtrot - shouted.length;
              var label = total > 100 ? 'a rather large total for such a small example' : 'a rather small total for such a large example';
              var grouped = (argumentAlpha + argumentBravo
                             + argumentCharlie);
              if (total > 6) total--;
              var pick = if (total > 3) 'many' else 'few';
              if (total == 0) {
              }
              var emptyCallback = function() {
              };
              var arrowCallback = () -> {
              };
              if (grouped > 1) {
                   total += grouped;
              } else if (label.length > 3) {
                   total -= grouped;
              } else {
                   total = 0;
              }
              do {
                   total--;
              } while (total > 99);
              try {
                   fLong(total + 100, total + 200, total + 300, total + 400, total + 500, total + 600);
              } catch (ignored:String) {}
              #if debug
              trace('debug build');
              #else
              trace('release build');
              #end
              return
                   total;
         }

         function fEmpty() {
         }

         function fQuick() return 'fast';

         function fMerge(base:{> Iterable<String>,
              var label:String;
         }) {
              return base.label;
         }

         public function new() {}
    }
    """;
  @org.intellij.lang.annotations.Language("Haxe")
  public static final String BLANK_LINES_CODE_SAMPLE = """
    /*
     * File header comment.
     */
    package foo.bar;
    import a.b.SomeClass;
    import a.b.SomeWidget;
    using someUtil;
    interface Drawable {}

    interface Resizable {}
    class Foo {


         var counter:Int = 0;
         var total:Int = 1;
         public function new() {
         }
         public static function main() {

              trace("Hello!");


         }

    }
    class Bar {}
    """;
}
