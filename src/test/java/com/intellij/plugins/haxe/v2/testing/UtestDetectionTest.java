package com.intellij.plugins.haxe.v2.testing;

import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures mirror the crypto project's shape: a project-local {@code unit.Test}
 * base extending a stub {@code utest.Test} (implementing a stub
 * {@code utest.ITest}), so detection is exercised over a transitive chain, not a
 * direct extends.
 */
@DisplayName("Test detection: utest")
public class UtestDetectionTest extends HaxeCodeInsightFixtureTestCase {

  private final UtestFramework framework = new UtestFramework();

  @Override
  protected String getBasePath() {
    return "/testing/";
  }

  private void configureFixtureProject() {
    myFixture.configureByFiles("utest/ITest.hx", "utest/Test.hx", "unit/Test.hx",
                               "cases/MathTest.hx", "cases/InterfaceStyleTest.hx", "cases/NotATest.hx");
  }

  @Test
  @DisplayName("class through project local base is detected")
  public void testClassThroughProjectLocalBaseIsDetected() {
    configureFixtureProject();
    assertTrue(framework.isTestClass(classByQName("cases.MathTest")));
  }

  @Test
  @DisplayName("interface style class is detected")
  public void testInterfaceStyleClassIsDetected() {
    configureFixtureProject();
    assertTrue(framework.isTestClass(classByQName("cases.InterfaceStyleTest")));
  }

  @Test
  @DisplayName("plain class is not detected")
  public void testPlainClassIsNotDetected() {
    configureFixtureProject();
    assertFalse(framework.isTestClass(classByQName("cases.NotATest")));
  }

  @Test
  @DisplayName("marker interface itself is not detected")
  public void testMarkerInterfaceItselfIsNotDetected() {
    configureFixtureProject();
    assertFalse(framework.isTestClass(classByQName("utest.ITest")));
  }

  @Test
  @DisplayName("methods with test and spec prefix are detected")
  public void testMethodsWithTestAndSpecPrefixAreDetected() {
    configureFixtureProject();
    HaxeClass mathTest = classByQName("cases.MathTest");
    assertTrue(framework.isTestMethod(methodOf(mathTest, "testAddition")));
    assertTrue(framework.isTestMethod(methodOf(mathTest, "specRounding")));
    assertTrue(framework.isTestMethod(methodOf(classByQName("cases.InterfaceStyleTest"), "testDirect")));
  }

  @Test
  @DisplayName("helper private and static methods are not detected")
  public void testHelperPrivateAndStaticMethodsAreNotDetected() {
    configureFixtureProject();
    HaxeClass mathTest = classByQName("cases.MathTest");
    assertFalse(framework.isTestMethod(methodOf(mathTest, "helperCompute")), "no test/spec prefix");
    assertFalse(framework.isTestMethod(methodOf(mathTest, "testPrivateSetup")), "not public");
    assertFalse(framework.isTestMethod(methodOf(mathTest, "testStaticFactory")), "not an instance method");
  }

  @Test
  @DisplayName("test shaped method on plain class is not detected")
  public void testTestShapedMethodOnPlainClassIsNotDetected() {
    configureFixtureProject();
    assertFalse(framework.isTestMethod(methodOf(classByQName("cases.NotATest"), "testLooking")));
  }

  @Test
  @DisplayName("reporting and filter args follow utest defines")
  public void testReportingAndFilterArgsFollowUtestDefines() {
    assertEquals(List.of("-D", "teamcity"), framework.reportingArgs(null, null, false));
    assertEquals(List.of("-D", "teamcity",
                         "-D", "teamcity_suite_name=Target: Neko",
                         "-cp", "reporter-root",
                         "--macro", "intellij_utest.Macro.init()"),
                 framework.reportingArgs("Target: Neko", "reporter-root", true));
    assertEquals(List.of("-D", "teamcity"),
                 framework.reportingArgs(null, "reporter-root", false),
                 "the live-reporting toggle drops the optional injection, batch reporting stays");
    assertEquals(List.of("-D", "UTEST_PATTERN=MathTest.testAddition"),
                 framework.filterArgs("MathTest.testAddition"));
    assertTrue(framework.filterArgs(null).isEmpty());
  }

  private HaxeClass classByQName(String qName) {
    HaxeClass haxeClass = HaxeResolveUtil.findClassByQName(qName, getPsiManager(),
                                                           GlobalSearchScope.allScope(getProject()));
    assertNotNull(haxeClass, "fixture class not found: " + qName);
    return haxeClass;
  }

  private HaxeMethod methodOf(HaxeClass haxeClass, String name) {
    for (PsiMethod method : haxeClass.getMethods()) {
      if (name.equals(method.getName()) && method instanceof HaxeMethod haxeMethod) {
        return haxeMethod;
      }
    }
    fail("fixture method not found: " + haxeClass.getQualifiedName() + "." + name);
    return null;
  }
}
