package com.intellij.plugins.haxe.ide.completion;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * The text a type completion leaves behind. A type declared inside another
 * module is written with the module in its path ({@code pack.Module.Type}),
 * a module's main type without it ({@code pack.Module}).
 */
@DisplayName("Completion: qualified type insertion")
public class HaxeQualifiedTypeCompletionTest extends HaxeLightFixtureTestCase {

  private static final String REFLECT_HX_NAME = "pack/sub/Reflect.hx";
  private static final String REFLECT_HX_SOURCE = """
    package pack.sub;

    class Reflect {}

    class Kind {
      public function new(value:Int) {}
    }
    """;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("a secondary type after its package qualifier gets its module, once")
  public void testASecondaryTypeAfterItsPackageQualifierGetsItsModuleOnce() {
    myFixture.addFileToProject(REFLECT_HX_NAME, REFLECT_HX_SOURCE);
    myFixture.configureByText("Main.hx", """
      class Main {
        static function main() {
          pack.sub.Kin<caret>;
        }
      }
      """);

    myFixture.completeBasic();

    myFixture.checkResult("""
      class Main {
        static function main() {
          pack.sub.Reflect.Kind<caret>;
        }
      }
      """);
  }

  @Test
  @DisplayName("a constructed secondary type keeps the caret inside its arguments")
  public void testAConstructedSecondaryTypeKeepsTheCaretInsideItsArguments() {
    myFixture.addFileToProject(REFLECT_HX_NAME, REFLECT_HX_SOURCE);
    myFixture.configureByText("Main.hx", """
      class Main {
        static function main() {
          new pack.sub.Kin<caret>;
        }
      }
      """);

    myFixture.completeBasic();

    myFixture.checkResult("""
      class Main {
        static function main() {
          new pack.sub.Reflect.Kind(<caret>);
        }
      }
      """);
  }

  @Test
  @DisplayName("a module main type after its package qualifier stays as typed")
  public void testAModuleMainTypeAfterItsPackageQualifierStaysAsTyped() {
    myFixture.addFileToProject(REFLECT_HX_NAME, REFLECT_HX_SOURCE);
    myFixture.configureByText("Main.hx", """
      class Main {
        static function main() {
          pack.sub.Refl<caret>;
        }
      }
      """);

    // the module Reflect.hx is offered beside its main type; the type is the one picked here
    LookupElement[] items = myFixture.completeBasic();
    LookupElement typeItem = Arrays.stream(items)
      .filter(item -> item.getPsiElement() instanceof HaxeClass)
      .findFirst()
      .orElseThrow();
    myFixture.getLookup().setCurrentItem(typeItem);
    myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

    myFixture.checkResult("""
      class Main {
        static function main() {
          pack.sub.Reflect<caret>;
        }
      }
      """);
  }

  @Test
  @DisplayName("a secondary type its module import brings in stays short")
  public void testASecondaryTypeItsModuleImportBringsInStaysShort() {
    myFixture.addFileToProject(REFLECT_HX_NAME, REFLECT_HX_SOURCE);
    myFixture.configureByText("Main.hx", """
      import pack.sub.Reflect;

      class Main {
        static function main() {
          var k:Kin<caret>;
        }
      }
      """);

    myFixture.completeBasic();

    myFixture.checkResult("""
      import pack.sub.Reflect;

      class Main {
        static function main() {
          var k:Kind<caret>;
        }
      }
      """);
  }
}
