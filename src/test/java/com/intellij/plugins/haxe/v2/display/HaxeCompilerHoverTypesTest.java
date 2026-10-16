package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.editor.Document;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.plugins.haxe.display.protocol.Position;
import com.intellij.plugins.haxe.display.protocol.Range;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerHoverTypes.Scope;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerHoverTypes.Target;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A batch asks about every covered declaration of a file that has no type
 * tag, at its name, and expects the compiler's answer to report exactly that
 * name's range; the local scope leaves the class members out.
 */
@DisplayName("Compiler types: hover targets")
public class HaxeCompilerHoverTypesTest extends HaxeLightFixtureTestCase {

  private static final String MAIN_HX_SOURCE = """
    enum Opt {
      Some(v:Int);
      None;
    }
    class Main {
      static var count = 1;
      static function run(size, total:Int) {
        var sum = size + total;
        switch (Some(sum)) {
          case Some(v): trace(v);
          case None:
        }
      }
      public function new() {}
    }
    """;

  @Override
  protected String getBasePath() {
    return "";
  }

  @Test
  @DisplayName("every untyped covered declaration becomes a target at its name")
  public void testEveryUntypedCoveredDeclarationBecomesATargetAtItsName() {
    PsiFile main = myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);
    Document document = myFixture.getEditor().getDocument();

    List<Target> targets = HaxeCompilerHoverTypes.targetsOf(main, document, Scope.ALL);

    assertEquals(List.of("count", "run", "size", "sum", "v"), names(targets, document),
                 "total and the enum constructor's v carry type tags; the constructor has no type to show");
    Target count = targets.getFirst();
    assertEquals(new Range(new Position(5, 13), new Position(5, 18)), count.expectedRange(), "0-based line and character of the name");
    assertEquals(5, count.line());
  }

  @Test
  @DisplayName("the local scope leaves the class members out")
  public void testTheLocalScopeLeavesTheClassMembersOut() {
    PsiFile main = myFixture.configureByText("Main.hx", MAIN_HX_SOURCE);
    Document document = myFixture.getEditor().getDocument();

    List<Target> targets = HaxeCompilerHoverTypes.targetsOf(main, document, Scope.LOCALS);

    assertEquals(List.of("sum", "v"), names(targets, document), "the field, the method and its parameter are members");
  }

  private static List<String> names(List<Target> targets, Document document) {
    return targets.stream().map(target -> document.getText().substring(target.offset(), target.offset() + nameLength(target))).toList();
  }

  private static int nameLength(Target target) {
    return target.expectedRange().end().character() - target.expectedRange().start().character();
  }
}
