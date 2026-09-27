package com.intellij.plugins.haxe.lang;

import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HaxeCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reformat reaches inside INACTIVE conditional branches through their lazily
 * parsed sub-trees - same rules as active code, like haxe-formatter. Token
 * soup and toggled-off branches are preserved verbatim. Byte parity with the
 * reference is pinned separately by the conditional-inactive fixture.
 */
@DisplayName("Formatting: inactive conditional branches")
public class HaxeInactiveFormattingTest extends HaxeLightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/formatter/";
  }

  private static final String MESSY_BRANCH_SOURCE = """
    class Main {
        static function main() {
            #if js
    trace(   "js"  ,1+2 );
            #end
            trace("live");
        }
    }
    """;

  @Test
  @DisplayName("dead statements format with the normal rules")
  public void testDeadStatementsFormatWithTheNormalRules() {
    assertEquals("""
      class Main {
          static function main() {
              #if js
              trace("js", 1 + 2);
              #end
              trace("live");
          }
      }
      """, reformat(settings -> { }, MESSY_BRANCH_SOURCE));
  }

  @Test
  @DisplayName("toggle off preserves the branch verbatim")
  public void testToggleOffPreservesTheBranchVerbatim() {
    Consumer<CodeStyleSettings> toggleOff = settings -> {
      HaxeCodeStyleSettings haxe = settings.getCustomSettings(HaxeCodeStyleSettings.class);
      haxe.FORMAT_INACTIVE_BRANCHES = false;
      // the comment passes rewrite comment text; a preserved branch is off limits to them too
      haxe.ADD_LINE_COMMENT_SPACE = true;
      haxe.REINDENT_MULTILINE_COMMENTS = true;
    };
    String source = """
      class Main {
          static function main() {
              #if js
      trace(   "js"  ,1+2 );
      //js only
          /* first
           second */
              #end
              //live
          }
      }
      """;

    String result = reformat(toggleOff, source);
    assertTrue(result.contains("trace(   \"js\"  ,1+2 );"), "the branch text must stay untouched:\n" + result);
    assertTrue(result.contains("\n//js only\n"), "a line comment in the branch keeps its shape:\n" + result);
    assertTrue(result.contains("\n    /* first\n     second */\n"), "a block comment in the branch keeps its shape:\n" + result);
    assertTrue(result.contains("// live"), "comments outside the branch still normalize:\n" + result);
  }

  @Test
  @DisplayName("if statements in dead branches get brace spacing")
  public void testIfStatementsInDeadBranchesGetBraceSpacing() {
    String source = """
      class Main {
          static function main() {
              #if js
              if(true)  {   trace("dead"); }
              #end
          }
      }
      """;

    String result = reformat(settings -> { }, source);
    assertTrue(result.contains("if (true) {"), "dead if statements take the same brace spacing as live ones:\n" + result);
  }

  @Test
  @DisplayName("token soup branches are preserved verbatim")
  public void testTokenSoupBranchesArePreservedVerbatim() {
    String source = """
      class Main {
          static function main() {
              var x = 1 #if truthy < #else > #end 2;
          }
      }
      """;

    assertEquals(source, reformat(settings -> { }, source), "unstructurable branches stay byte-identical");
  }

  private String reformat(Consumer<CodeStyleSettings> configure, String source) {
    return reformat("Inactive.hx", configure, source);
  }
}
