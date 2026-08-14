package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.Location;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

  @Test
  @DisplayName("test name resolves to the method")
  public void testTestNameResolvesToTheMethod() {
    configureFixtureProject();
    PsiElement element = locate("cases.MathTest.testAddition");
    assertInstanceOf(HaxeMethod.class, element);
    assertEquals("testAddition", ((HaxeMethod)element).getName());
  }

  @Test
  @DisplayName("suite name resolves to the class")
  public void testSuiteNameResolvesToTheClass() {
    configureFixtureProject();
    PsiElement element = locate("cases.MathTest");
    assertInstanceOf(HaxeClass.class, element);
    assertEquals("cases.MathTest", ((HaxeClass)element).getQualifiedName());
  }

  @Test
  @DisplayName("underscored package maps back to dots")
  public void testUnderscoredPackageMapsBackToDots() {
    configureFixtureProject();
    PsiElement element = locate("pack_sub.DeepTest.testDeep");
    assertInstanceOf(HaxeMethod.class, element);
    assertEquals("testDeep", ((HaxeMethod)element).getName());
    assertInstanceOf(HaxeClass.class, locate("pack_sub.DeepTest"));
  }

  @Test
  @DisplayName("default package leading dot is stripped")
  public void testDefaultPackageLeadingDotIsStripped() {
    configureFixtureProject();
    assertInstanceOf(HaxeClass.class, locate(".RootTest"));
    PsiElement element = locate(".RootTest.testRoot");
    assertInstanceOf(HaxeMethod.class, element);
    assertEquals("testRoot", ((HaxeMethod)element).getName());
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
    assertTrue(alpha.getContainingFile().getVirtualFile().getPath().contains("/alpha/"),
               "the alpha build resolves its own class, got: " + alpha.getContainingFile().getVirtualFile().getPath());

    PsiElement beta = locate("CalculatorTest.testAdd?build=" + betaBuild);
    assertTrue(beta.getContainingFile().getVirtualFile().getPath().contains("/beta/"),
               "the beta build resolves its own class, got: " + beta.getContainingFile().getVirtualFile().getPath());
  }

  @Test
  @DisplayName("unknown names and foreign protocols yield nothing")
  public void testUnknownNamesAndForeignProtocolsYieldNothing() {
    configureFixtureProject();
    assertNull(locate("no.such.ClassAnywhere.testNothing"));
    assertTrue(HaxeTestLocator.INSTANCE.getLocation(
      "java:test", "cases.MathTest", getProject(), GlobalSearchScope.allScope(getProject())).isEmpty());
  }
}
