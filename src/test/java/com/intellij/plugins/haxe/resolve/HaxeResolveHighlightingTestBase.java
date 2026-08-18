package com.intellij.plugins.haxe.resolve;

import com.intellij.lang.LanguageAnnotators;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.annotator.HaxeUnresolvedTypeAnnotator;
import com.intellij.plugins.haxe.ide.inspections.HaxeUnresolvedSymbolInspection;
import com.intellij.util.ArrayUtil;
import com.intellij.plugins.haxe.HaxeLightProjectDescriptors;
import com.intellij.testFramework.LightProjectDescriptor;

/**
 * Base for the resolve-highlighting tests: the unresolved-type annotator plus
 * the unresolved-symbol inspection over a test-name-derived fixture.
 * Subclasses supply only their fixture directory via {@code getBasePath()}.
 */
public abstract class HaxeResolveHighlightingTestBase extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected LightProjectDescriptor lightProjectDescriptor() {
    return HaxeLightProjectDescriptors.WITH_TOOLKIT;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    setTestStyleSettings(2);
  }

  protected void doTest(String... additionalFiles) {
    myFixture.configureByFiles(ArrayUtil.mergeArrays(new String[]{getTestName(false) + ".hx"}, additionalFiles));
    LanguageAnnotators.INSTANCE.addExplicitExtension(HaxeLanguage.INSTANCE, new HaxeUnresolvedTypeAnnotator());
    myFixture.enableInspections(HaxeUnresolvedSymbolInspection.class);
    myFixture.testHighlighting(true, true, true);
  }
}
