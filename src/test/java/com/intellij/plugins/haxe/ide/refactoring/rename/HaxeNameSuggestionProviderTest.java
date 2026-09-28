package com.intellij.plugins.haxe.ide.refactoring.rename;

import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.util.PsiTreeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rename: name suggestions")
public class HaxeNameSuggestionProviderTest extends HaxeLightFixtureTestCase {

  private static final String TYPED_LOCAL_SOURCE = """
    class Main {
    	static function main() {
    		var <caret>x:String = "a";
    	}
    }
    """;
  private static final String INITIALIZED_LOCAL_SOURCE = """
    class Box {
    	public function new() {}
    }
    class Main {
    	static function main() {
    		var <caret>x = new Box();
    	}
    }
    """;
  private static final String PARAMETER_SOURCE = """
    class Main {
    	static function run(<caret>count:Int) {}
    }
    """;
  private static final String METHOD_SOURCE = """
    class Main {
    	static function <caret>run() {}
    }
    """;
  private static final String CLASHING_LOCAL_SOURCE = """
    class Main {
    	static function main() {
    		var str = "b";
    		var <caret>x:String = "a";
    	}
    }
    """;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("a typed local suggests names from its type")
  public void testATypedLocalSuggestsNamesFromItsType() {
    Set<String> names = suggestionsAtCaret(TYPED_LOCAL_SOURCE);

    assertTrue(names.contains("str"), names.toString());
    assertTrue(names.contains("string"), names.toString());
  }

  @Test
  @DisplayName("an initialized local suggests names from its initializer")
  public void testAnInitializedLocalSuggestsNamesFromItsInitializer() {
    Set<String> names = suggestionsAtCaret(INITIALIZED_LOCAL_SOURCE);

    assertTrue(names.contains("box"), names.toString());
  }

  @Test
  @DisplayName("a parameter suggests names from its type")
  public void testAParameterSuggestsNamesFromItsType() {
    Set<String> names = suggestionsAtCaret(PARAMETER_SOURCE);

    assertTrue(names.contains("i"), names.toString());
  }

  @Test
  @DisplayName("a method gets no suggestions")
  public void testAMethodGetsNoSuggestions() {
    myFixture.configureByText("Main.hx", METHOD_SOURCE);
    Set<String> names = new LinkedHashSet<>();

    SuggestedNameInfo info = new HaxeNameSuggestionProvider().getSuggestedNames(componentAtCaret(), null, names);

    assertNull(info);
    assertTrue(names.isEmpty(), names.toString());
  }

  @Test
  @DisplayName("a name in use nearby is avoided")
  public void testANameInUseNearbyIsAvoided() {
    Set<String> names = suggestionsAtCaret(CLASHING_LOCAL_SOURCE);

    assertFalse(names.contains("str"), "str is taken by the sibling local: " + names);
    assertTrue(names.contains("str1"), names.toString());
  }

  private Set<String> suggestionsAtCaret(String source) {
    myFixture.configureByText("Main.hx", source);
    Set<String> names = new LinkedHashSet<>();
    SuggestedNameInfo info = new HaxeNameSuggestionProvider().getSuggestedNames(componentAtCaret(), null, names);
    assertNotNull(info, "the provider answers for a value declaration");
    return names;
  }

  private PsiElement componentAtCaret() {
    PsiElement leaf = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
    HaxeNamedComponent component = PsiTreeUtil.getParentOfType(leaf, HaxeNamedComponent.class);
    assertNotNull(component);
    return component;
  }
}
