package com.intellij.plugins.haxe.lang;

import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.ide.formatter.settings.HxformatCodeStyle;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform's second reformat: a reformat that kept custom line breaks
 * (KEEP_LINE_BREAKS) reports them, and repeating it offers to drop them by
 * rerunning with the flag off. These tests cover both passes under the
 * hxformat defaults profile.
 */
@DisplayName("Formatting: second reformat")
public class HaxeSecondReformatTest extends HaxeLightFixtureTestCase {
  private static final String WRAPPED_CALL_SOURCE = """
    class Main {
    	function draw(c:Style) {
    		strokeCommands.lineGradientStyle(c.type,
    			c.colors,
    			c.alphas,
    			c.ratios);
    	}
    }
    """;

  @Override
  protected String getBasePath() {
    return "/formatter/comparison/";
  }

  @Test
  @DisplayName("haxe takes part in the second reformat flow")
  public void testHaxeTakesPartInTheSecondReformatFlow() {
    LanguageCodeStyleSettingsProvider provider = LanguageCodeStyleSettingsProvider.forLanguage(HaxeLanguage.INSTANCE);

    assertTrue(provider.usesCommonKeepLineBreaks(), "the platform only records kept breaks for languages that opt in");
  }

  @Test
  @DisplayName("first pass keeps custom line breaks")
  public void testFirstPassKeepsCustomLineBreaks() {
    String formatted = reformat("Main.hx", HxformatCodeStyle::applyDefaults, WRAPPED_CALL_SOURCE);

    assertEquals(WRAPPED_CALL_SOURCE, formatted);
  }

  @Test
  @DisplayName("second pass joins custom line breaks")
  public void testSecondPassJoinsCustomLineBreaks() {
    String formatted = reformat("Main.hx", HaxeSecondReformatTest::secondPass, WRAPPED_CALL_SOURCE);

    assertEquals("""
      class Main {
      	function draw(c:Style) {
      		strokeCommands.lineGradientStyle(c.type, c.colors, c.alphas, c.ratios);
      	}
      }
      """, formatted);
  }

  @Test
  @DisplayName("second pass rewraps a joined line past the margin")
  public void testSecondPassRewrapsAJoinedLinePastTheMargin() {
    String source = """
      class Main {
      	function copy():Options {
      		return new Options(firstValue, secondValue, thirdValue, fourthValue, fifthValue, sixthValue, seventhValue, eighthValue,
      			ninthValue,
      			tenthValue,
      			eleventhValue,
      			twelfthValue);
      	}
      }
      """;

    String formatted = reformat("Main.hx", HaxeSecondReformatTest::secondPass, source);

    assertEquals("""
      class Main {
      	function copy():Options {
      		return new Options(firstValue, secondValue, thirdValue, fourthValue, fifthValue, sixthValue, seventhValue, eighthValue, ninthValue, tenthValue,
      			eleventhValue, twelfthValue);
      	}
      }
      """, formatted);
  }

  @Test
  @DisplayName("second pass keeps object literal and anonymous type layouts")
  public void testSecondPassKeepsObjectLiteralAndAnonymousTypeLayouts() {
    String source = """
      typedef Base = {
      	var id:Int;
      	var name:String;
      }

      class Main {
      	function make() {
      		var point = {
      			x: 1,
      			y: 2
      		};
      		var values = [
      			1,
      			2
      		];
      	}
      }
      """;

    String formatted = reformat("Main.hx", HaxeSecondReformatTest::secondPass, source);

    assertEquals("""
      typedef Base = {
      	var id:Int;
      	var name:String;
      }

      class Main {
      	function make() {
      		var point = {
      			x: 1,
      			y: 2
      		};
      		var values = [1, 2];
      	}
      }
      """, formatted);
  }

  @Test
  @DisplayName("second pass keeps next line braces on a switch")
  public void testSecondPassKeepsNextLineBracesOnASwitch() {
    String source = """
      class Main {
      	function pick(type:Int) {
      		switch (type)
      		{
      			case 1:
      				trace(1);
      		}
      	}
      }
      """;

    String formatted = reformat("Main.hx", HaxeSecondReformatTest::allmanSecondPass, source);

    assertEquals("""
      class Main
      {
      	function pick(type:Int)
      	{
      		switch (type)
      		{
      			case 1:
      				trace(1);
      		}
      	}
      }
      """, formatted);
  }

  /** The hxformat defaults with custom line breaks dropped, as the platform reruns the reformat. */
  private static void secondPass(CodeStyleSettings settings) {
    HxformatCodeStyle.applyDefaults(settings);
    settings.getCommonSettings(HaxeLanguage.INSTANCE).KEEP_LINE_BREAKS = false;
  }

  /** The second pass under openfl's brace config (lineEnds.leftCurly=both). */
  private static void allmanSecondPass(CodeStyleSettings settings) {
    secondPass(settings);
    CommonCodeStyleSettings common = settings.getCommonSettings(HaxeLanguage.INSTANCE);
    common.BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
    common.METHOD_BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
  }
}
