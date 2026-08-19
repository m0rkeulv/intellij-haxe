package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.Location;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Resolves the names utest's TeamCity reporter emits back to PSI:
 * {@code pack_with_underscores.Class[.method]}, with an empty leading segment
 * for the default package.
 */
@DisplayName("Test runner: locator")
public class HaxeTestLocatorTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/testing/";
  }

  private void configureFixtureProject() {
    myFixture.configureByFiles("utest/ITest.hx", "utest/Test.hx", "unit/Test.hx",
                               "cases/MathTest.hx", "pack/sub/DeepTest.hx", "RootTest.hx");
  }

  private PsiElement locate(String name) {
    List<Location> locations = HaxeTestLocator.INSTANCE.getLocation(
      HaxeTestLocator.PROTOCOL, name, getProject(), GlobalSearchScope.allScope(getProject()));
    return locations.isEmpty() ? null : locations.get(0).getPsiElement();
  }

  /**
   * (reporter name, resolved PSI kind, resolved name; null skips the name
   * check). Underscored package segments map back to dots; the default
   * package reports with an empty leading segment.
   */
  static final List<Arguments> LOCATED_NAMES = List.of(
    arguments("cases.MathTest.testAddition", HaxeMethod.class, "testAddition"),
    arguments("cases.MathTest", HaxeClass.class, "cases.MathTest"),
    arguments("pack_sub.DeepTest.testDeep", HaxeMethod.class, "testDeep"),
    arguments("pack_sub.DeepTest", HaxeClass.class, null),
    arguments(".RootTest", HaxeClass.class, null),
    arguments(".RootTest.testRoot", HaxeMethod.class, "testRoot"));

  @ParameterizedTest(name = "{0}")
  @FieldSource("LOCATED_NAMES")
  @DisplayName("reporter names resolve to their psi elements")
  public void testReporterNamesResolveToTheirPsiElements(String reportedName, Class<?> kind, String expectedName) {
    configureFixtureProject();
    PsiElement element = locate(reportedName);
    assertInstanceOf(kind, element);
    if (expectedName != null) {
      String name = element instanceof HaxeMethod method ? method.getName() : ((HaxeClass)element).getQualifiedName();
      assertEquals(expectedName, name);
    }
  }

  @Test
  @DisplayName("build suffix breaks same name ties between sibling projects")
  public void testBuildSuffixBreaksSameNameTiesBetweenSiblingProjects() {
    // one module can hold several sub-projects declaring the SAME
    // default-package class - the run's tests build file picks its own
    myFixture.addFileToProject("alpha/test/CalculatorTest.hx",
                               "class CalculatorTest {\n  public function testAdd():Void {}\n}\n");
    myFixture.addFileToProject("beta/test/CalculatorTest.hx",
                               "class CalculatorTest {\n  public function testAdd():Void {}\n}\n");
    String alphaBuild = myFixture.addFileToProject("alpha/test.hxml", "-cp test\n--main TestMain\n--interp\n")
      .getVirtualFile().getPath();
    String betaBuild = myFixture.addFileToProject("beta/test.hxml", "-cp test\n--main TestMain\n--interp\n")
      .getVirtualFile().getPath();

    PsiElement alpha = locate("CalculatorTest.testAdd?build=" + alphaBuild);
    assertInstanceOf(HaxeMethod.class, alpha);
    String alphaPath = alpha.getContainingFile().getVirtualFile().getPath();
    assertTrue(alphaPath.contains("/alpha/"), "the alpha build resolves its own class, got: " + alphaPath);

    PsiElement beta = locate("CalculatorTest.testAdd?build=" + betaBuild);
    assertInstanceOf(HaxeMethod.class, beta);
    String betaPath = beta.getContainingFile().getVirtualFile().getPath();
    assertTrue(betaPath.contains("/beta/"), "the beta build resolves its own class, got: " + betaPath);
  }

  @Test
  @DisplayName("unknown names and foreign protocols yield nothing")
  public void testUnknownNamesAndForeignProtocolsYieldNothing() {
    configureFixtureProject();
    assertNull(locate("no.such.ClassAnywhere.testNothing"));
    var foreignProtocolMatches = HaxeTestLocator.INSTANCE.getLocation(
      "java:test", "cases.MathTest", getProject(), GlobalSearchScope.allScope(getProject()));

    assertTrue(foreignProtocolMatches.isEmpty());
  }
}
