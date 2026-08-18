package com.intellij.plugins.haxe.ide;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.plugins.haxe.ide.annotator.HaxeSemanticAnnotatorInspections;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel.*;
import com.intellij.plugins.haxe.HaxeLightProjectDescriptors;
import com.intellij.testFramework.LightProjectDescriptor;

/**
 * Language-level annotations, one fixture per test: each file under
 * testData/annotation.languagelevel carries the expected error/warning markup
 * for the level its test selects. Covers gating of level-dependent semantics,
 * "requires Haxe X" for newer syntax and "removed in Haxe X" for retired
 * constructs.
 */
@DisplayName("Annotation: language level features")
public class HaxeLanguageLevelGatingTest extends HaxeSemanticAnnotatorTestBase {

  // the LEVEL must be what drives pre-4.2 `is` semantics here, not the manual opt-in inspection
  private static final Set<Class<? extends LocalInspectionTool>> MANUAL_OPT_INS =
    Set.of(HaxeSemanticAnnotatorInspections.IsTypeExpressionInspection4dot1Compatible.class);

  @Override
  protected LightProjectDescriptor lightProjectDescriptor() {
    return HaxeLightProjectDescriptors.WITH_TOOLKIT;
  }

  @Override
  public void tearDown() throws Exception {
    // project-level setting survives into sibling tests otherwise
    setLevel(HaxeLanguageLevel.latest());
    super.tearDown();
  }

  @Override
  protected String getBasePath() {
    return "/annotation.languagelevel/";
  }

  private void setLevel(HaxeLanguageLevel level) {
    HaxeCompilerSettings.getInstance(myFixture.getProject()).setDefaultLanguageLevel(level);
  }

  private void doTestAtLevel(HaxeLanguageLevel level) throws Exception {
    setLevel(level);
    doTestSkippingAnnotators(MANUAL_OPT_INS);
  }

  // ---- 3.4 ----

  @Test
  @DisplayName("final keyword below 4.0")
  public void testFinalKeywordBelow40() throws Exception {
    doTestAtLevel(HAXE_3_4);
  }

  @Test
  @DisplayName("arrow function below 4.0")
  public void testArrowFunctionBelow40() throws Exception {
    doTestAtLevel(HAXE_3_4);
  }

  @Test
  @DisplayName("enum abstract below 4.0")
  public void testEnumAbstractBelow40() throws Exception {
    doTestAtLevel(HAXE_3_4);
  }

  @Test
  @DisplayName("custom accessor names below 4.0")
  public void testCustomAccessorNamesBelow40() throws Exception {
    doTestAtLevel(HAXE_3_4);
  }

  // ---- 4.0 ----

  @Test
  @DisplayName("final keyword at 4.0")
  public void testFinalKeywordAt40() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  @Test
  @DisplayName("arrow function at 4.0")
  public void testArrowFunctionAt40() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  @Test
  @DisplayName("enum abstract at 4.0")
  public void testEnumAbstractAt40() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  @Test
  @DisplayName("custom accessor names removed at 4.0")
  public void testCustomAccessorNamesRemovedAt40() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  @Test
  @DisplayName("untyped catch below 4.1")
  public void testUntypedCatchBelow41() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  @Test
  @DisplayName("Std.is not deprecated below 4.1")
  public void testStdIsNotDeprecatedBelow41() throws Exception {
    doTestAtLevel(HAXE_4_0);
  }

  // ---- 4.1 ----

  @Test
  @DisplayName("untyped catch at 4.1")
  public void testUntypedCatchAt41() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  @Test
  @DisplayName("abstract method below 4.2")
  public void testAbstractMethodBelow42() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  @Test
  @DisplayName("abstract modifier below 4.2")
  public void testAbstractModifierBelow42() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  @Test
  @DisplayName("module level function below 4.2")
  public void testModuleLevelFunctionBelow42() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  @Test
  @DisplayName("is expression pre 4.2 semantics")
  public void testIsExpressionPre42Semantics() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  @Test
  @DisplayName("Std.is deprecated at 4.1")
  public void testStdIsDeprecatedAt41() throws Exception {
    doTestAtLevel(HAXE_4_1);
  }

  // ---- 4.2 ----

  @Test
  @DisplayName("abstract method in non abstract class at 4.2")
  public void testAbstractMethodInNonAbstractClassAt42() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("abstract class at 4.2")
  public void testAbstractClassAt42() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("module level function at 4.2")
  public void testModuleLevelFunctionAt42() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("is expression at 4.2")
  public void testIsExpressionAt42() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("null coalescing below 4.3")
  public void testNullCoalescingBelow43() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("default type parameters below 4.3")
  public void testDefaultTypeParametersBelow43() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  @Test
  @DisplayName("safe navigation below 4.3")
  public void testSafeNavigationBelow43() throws Exception {
    doTestAtLevel(HAXE_4_2);
  }

  // ---- 4.3 ----

  @Test
  @DisplayName("null coalescing at 4.3")
  public void testNullCoalescingAt43() throws Exception {
    doTestAtLevel(HAXE_4_3);
  }

  @Test
  @DisplayName("default type parameters at 4.3")
  public void testDefaultTypeParametersAt43() throws Exception {
    doTestAtLevel(HAXE_4_3);
  }

  @Test
  @DisplayName("safe navigation at 4.3")
  public void testSafeNavigationAt43() throws Exception {
    doTestAtLevel(HAXE_4_3);
  }

  @Test
  @DisplayName("binary literals warn below 5.0")
  public void testBinaryLiteralsWarnBelow50() throws Exception {
    doTestAtLevel(HAXE_4_3);
  }

  // ---- 5.0 ----

  @Test
  @DisplayName("binary literals at 5.0")
  public void testBinaryLiteralsAt50() throws Exception {
    doTestAtLevel(HAXE_5_0);
  }
}
