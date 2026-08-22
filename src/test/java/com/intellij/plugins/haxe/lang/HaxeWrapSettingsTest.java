package com.intellij.plugins.haxe.lang;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wrap toggles against inline sources. The grammar wraps EVERY binary and
 * ternary operator into a composite element (additiveOperator,
 * questionOperator, ...) - matching the bare tokens silently disables the
 * sign-placement halves of these settings, which is what these tests pin.
 */
@DisplayName("Formatting: wrap settings")
public class HaxeWrapSettingsTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/formatter/";
  }

  @Test
  @DisplayName("ternary wrap forms")
  public void testTernaryWrapForms() {
    Consumer<CommonCodeStyleSettings> wrapAlways = common -> common.TERNARY_OPERATION_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;

    assertEquals("""
      class Main {
          static function main() {
              var total = 101;
              var label = total > 100 ?
                      'large' :
                      'small';
              trace(label);
          }
      }
      """, reformat(wrapAlways.andThen(common -> common.TERNARY_OPERATION_SIGNS_ON_NEXT_LINE = false), """
      class Main {
      	static function main() {
      		var total = 101;
      		var label = total > 100 ? 'large' : 'small';
      		trace(label);
      	}
      }
      """));

    assertEquals("""
      class Main {
          static function main() {
              var total = 101;
              var label = total > 100
                      ? 'large'
                      : 'small';
              trace(label);
          }
      }
      """, reformat(wrapAlways.andThen(common -> common.TERNARY_OPERATION_SIGNS_ON_NEXT_LINE = true), """
      class Main {
      	static function main() {
      		var total = 101;
      		var label = total > 100 ? 'large' : 'small';
      		trace(label);
      	}
      }
      """));
  }

  @Test
  @DisplayName("binary wrap sign placement")
  public void testBinaryWrapSignPlacement() {
    Consumer<CommonCodeStyleSettings> wrapAlways = common -> common.BINARY_OPERATION_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;
    String source = """
      class Main {
      	static function main() {
      		var total = 1 + 2 * 3;
      		trace(total);
      	}
      }
      """;

    assertEquals("""
      class Main {
          static function main() {
              var total = 1 +
              2 *
              3;
              trace(total);
          }
      }
      """, reformat(wrapAlways.andThen(common -> common.BINARY_OPERATION_SIGN_ON_NEXT_LINE = false), source));

    assertEquals("""
      class Main {
          static function main() {
              var total = 1
              + 2
              * 3;
              trace(total);
          }
      }
      """, reformat(wrapAlways.andThen(common -> common.BINARY_OPERATION_SIGN_ON_NEXT_LINE = true), source));
  }

  /**
   * Operand alignment anchors at the binary's first operand; for a binary
   * used as a call ARGUMENT that anchor sits mid-line and every argument
   * staircases deeper than the last - alignment applies only where the
   * expression owns its line.
   */
  @Test
  @DisplayName("binary alignment skips call arguments")
  public void testBinaryAlignmentSkipsCallArguments() {
    Consumer<CommonCodeStyleSettings> configure = common -> {
      common.BINARY_OPERATION_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;
      common.BINARY_OPERATION_SIGN_ON_NEXT_LINE = true;
      common.ALIGN_MULTILINE_BINARY_OPERATION = true;
    };

    assertEquals("""
      class Main {
          static function main() {
              var first = 1;
              var second = 2;
              var total = first
                          + second;
              trace(pick(first
              + 1, second
              + 2));
          }

          static function pick(a:Int, b:Int):Int {
              return a
                     + b;
          }
      }
      """, reformat(configure, """
      class Main {
      	static function main() {
      		var first = 1;
      		var second = 2;
      		var total = first + second;
      		trace(pick(first + 1, second + 2));
      	}

      	static function pick(a:Int, b:Int):Int {
      		return a + b;
      	}
      }
      """));
  }

  /** ALWAYS must break EVERY implements clause; only fill mode leaves the first one inline. */
  @Test
  @DisplayName("extends list wrap always breaks every clause")
  public void testExtendsListWrapAlwaysBreaksEveryClause() {
    assertEquals("""
      class Foo extends Base
              implements Drawable
              implements Resizable {
          public function new() {}
      }
      """, reformat(common -> common.EXTENDS_LIST_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS, """
      class Foo extends Base implements Drawable implements Resizable {
      	public function new() {}
      }
      """));
  }

  private String reformat(Consumer<CommonCodeStyleSettings> configure, String source) {
    CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(getProject()).clone();
    configure.accept(settings.getCommonSettings(HaxeLanguage.INSTANCE));
    CodeStyleSettingsManager.getInstance(getProject()).setTemporarySettings(settings);
    myFixture.configureByText("Wrap.hx", source);
    Runnable reformat = () -> CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
    WriteCommandAction.runWriteCommandAction(getProject(), reformat);
    return myFixture.getFile().getText();
  }
}
