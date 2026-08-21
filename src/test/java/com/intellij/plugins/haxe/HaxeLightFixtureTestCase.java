package com.intellij.plugins.haxe;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.plugins.haxe.util.HaxeTestUtils;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;

/**
 * Code-insight test over the platform's SHARED light project: one project
 * is reused for consecutive tests with an equal descriptor, skipping the
 * per-test project open and index wait of the heavy base. The fixture
 * surface is identical and per-test files roll back, but project-level
 * state (settings, inspection profile, trust) survives between tests - a
 * class that mutates it must reset it. The bare Haxe module (in-memory
 * source root only) is the default; a suite that needs the toolkit std
 * extends {@link HaxeToolkitLightFixtureTestCase}.
 */
public abstract class HaxeLightFixtureTestCase extends HaxeCodeInsightFixtureTestCase {

  /** Must return a SHARED instance from {@link HaxeLightProjectDescriptors} - project reuse is keyed on descriptor equality. */
  protected LightProjectDescriptor lightProjectDescriptor() {
    return HaxeLightProjectDescriptors.BARE;
  }

  @Override
  protected void setUp() throws Exception {
    IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
    TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder = factory.createLightFixtureBuilder(lightProjectDescriptor(), getName());
    myFixture = factory.createCodeInsightFixture(projectBuilder.getFixture(), new LightTempDirTestFixtureImpl(true));

    // the Flex plugin trips VfsRootAccess during setUp; the testdata root
    // covers fixture files AND the toolkit std a descriptor may mount
    VfsRootAccess.allowRootAccess(getTestRootDisposable(), PathManager.getPluginsPath());
    VfsRootAccess.allowRootAccess(getTestRootDisposable(), HaxeTestUtils.BASE_TEST_DATA_PATH);

    myFixture.setTestDataPath(getTestDataPath());
    myFixture.setUp();

    // type inference trips RecursionPrevention; the shared project outlives
    // the test, so these bind to the per-test disposable rather than the
    // project disposable the heavy setup uses
    RecursionManager.disableAssertOnRecursionPrevention(getTestRootDisposable());
    RecursionManager.disableMissedCacheAssertions(getTestRootDisposable());
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      dropTemporaryCodeStyleSettings();
    }
    finally {
      super.tearDown();
    }
  }

  /** A leftover temporary code style would leak into the next test of the shared project. */
  private void dropTemporaryCodeStyleSettings() {
    if (myFixture == null) return;
    CodeStyleSettingsManager.getInstance(myFixture.getProject()).dropTemporarySettings();
  }
}
