package com.intellij.plugins.haxe.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Doc comment interior indentation on reformat: under-indented lines rise to
 * the body indent, markdown depth beyond it stays, and the toggle turns the
 * whole treatment off. Byte parity with haxe-formatter is pinned separately
 * by the comparison suite's doc-comment-indent fixture.
 */
@DisplayName("Formatting: doc comment interior")
public class HaxeDocCommentFormatTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/formatter/";
  }

  private static final String COLUMN_ZERO_SOURCE = """
    class Main {
        /**
            Body line, hard-wrapped as
    wrapped at column zero

            \tdeeper markdown
        **/
        static function main() {
            trace(1);
        }
    }
    """;

  @Test
  @DisplayName("interior lines rise to the body indent")
  public void testInteriorLinesRiseToTheBodyIndent() {
    assertEquals("""
      class Main {
          /**
              Body line, hard-wrapped as
              wrapped at column zero

              \tdeeper markdown
          **/
          static function main() {
              trace(1);
          }
      }
      """, reformat(settings -> { }, COLUMN_ZERO_SOURCE));
  }

  @Test
  @DisplayName("toggle off keeps the interior untouched")
  public void testToggleOffKeepsTheInteriorUntouched() {
    Consumer<CodeStyleSettings> toggleOff =
      settings -> settings.getCustomSettings(HaxeCodeStyleSettings.class).FORMAT_DOC_COMMENTS = false;

    assertEquals(COLUMN_ZERO_SOURCE, reformat(toggleOff, COLUMN_ZERO_SOURCE));
  }

  @Test
  @DisplayName("starred style aligns stars and closer one space in")
  public void testStarredStyleAlignsStarsAndCloserOneSpaceIn() {
    assertEquals("""
      class Main {
          /**
           * Starred body.
           * @param x value
           */
          static function main(x:Int) {
              trace(x);
          }
      }
      """, reformat(settings -> { }, """
      class Main {
          /**
      * Starred body.
              * @param x value
          */
          static function main(x:Int) {
              trace(x);
          }
      }
      """));
  }

  @Test
  @DisplayName("zero column doc follows its scope while plain comments keep first column")
  public void testZeroColumnDocFollowsItsScopeWhilePlainCommentsKeepFirstColumn() {
    assertEquals("""
      class Main {
          /**
              docs for main
          **/
          static function main() {
              trace(1);
          }

      // disabled-code comment stays (keep-first-column default)
          static var x:Int = 1;
      }
      """, reformat(settings -> { }, """
      class Main {
      /**
      \tdocs for main
      **/
          static function main() {
              trace(1);
          }

      // disabled-code comment stays (keep-first-column default)
          static var x:Int = 1;
      }
      """));
  }

  private String reformat(Consumer<CodeStyleSettings> configure, String source) {
    CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(getProject()).clone();
    configure.accept(settings);
    CodeStyleSettingsManager.getInstance(getProject()).setTemporarySettings(settings);
    myFixture.configureByText("Doc.hx", source);
    Runnable reformat = () -> CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
    WriteCommandAction.runWriteCommandAction(getProject(), reformat);
    return myFixture.getFile().getText();
  }
}
