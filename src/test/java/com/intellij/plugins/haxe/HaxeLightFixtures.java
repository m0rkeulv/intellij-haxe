package com.intellij.plugins.haxe;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.plugins.haxe.util.HaxeTestUtils;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;

/**
 * Light-fixture counterpart of the heavy setup in
 * {@link HaxeCodeInsightFixtureTestCase}: the same
 * {@link CodeInsightTestFixture} surface, but over the platform's shared
 * light project (reused for consecutive tests with an equal descriptor) and
 * an in-memory temp file system - skipping the per-test project open and
 * its wait for indexing.
 */
public final class HaxeLightFixtures {

  private HaxeLightFixtures() {
  }

  /**
   * Builds and starts the fixture. Descriptors must be the shared instances
   * from {@link HaxeLightProjectDescriptors} - project reuse is keyed on
   * descriptor equality.
   */
  public static CodeInsightTestFixture setUpLightFixture(@NotNull LightProjectDescriptor descriptor,
                                                         @NotNull String testName,
                                                         @NotNull String testDataPath,
                                                         @NotNull Disposable testDisposable) throws Exception {
    IdeaTestFixtureFactory factory = IdeaTestFixtureFactory.getFixtureFactory();
    TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder = factory.createLightFixtureBuilder(descriptor, testName);
    LightTempDirTestFixtureImpl tempDir = new LightTempDirTestFixtureImpl(true);
    CodeInsightTestFixture fixture = factory.createCodeInsightFixture(projectBuilder.getFixture(), tempDir);

    // the Flex plugin trips VfsRootAccess during setUp; the testdata root
    // covers fixture files AND the toolkit std a descriptor may mount
    VfsRootAccess.allowRootAccess(testDisposable, PathManager.getPluginsPath());
    VfsRootAccess.allowRootAccess(testDisposable, HaxeTestUtils.BASE_TEST_DATA_PATH);

    fixture.setTestDataPath(testDataPath);
    fixture.setUp();

    // type inference trips RecursionPrevention; the shared project outlives
    // the test, so these bind to the per-test disposable rather than the
    // project disposable the heavy setup uses
    RecursionManager.disableAssertOnRecursionPrevention(testDisposable);
    RecursionManager.disableMissedCacheAssertions(testDisposable);
    return fixture;
  }
}
