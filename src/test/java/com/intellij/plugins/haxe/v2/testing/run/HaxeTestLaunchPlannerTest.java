package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeSectionSelectionStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeTargetSelectionStore;
import com.intellij.plugins.haxe.v2.testing.run.HaxeTestLaunchPlanner.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plans are derived from hxml fixtures only - nothing is compiled. Fixtures live
 * in {@code testData/testing/runner/}; the artifact-target files under
 * {@code targets/} exist purely to be parsed.
 */
@DisplayName("Test runner: launch planner")
public class HaxeTestLaunchPlannerTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/testing/runner/";
  }

  private String fixturePath(String relativePath) {
    VirtualFile file = myFixture.copyFileToProject(relativePath);
    return file.getPath();
  }

  @Test
  @DisplayName("interp build is a single stage carrying the reporting define")
  public void testInterpBuildIsASingleStageCarryingTheReportingDefine() throws ExecutionException {
    String path = fixturePath("test.hxml");
    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertTrue(plan.singleStage(), "interp compiles and runs as one process");
    assertTrue(plan.command().containsAll(List.of("-D", "teamcity")), "activation define missing: " + plan.command());
    assertTrue(plan.command().contains("test.hxml"), "the compile must reference the tests build file");
  }

  @Test
  @DisplayName("filter pattern becomes the utest pattern define")
  public void testFilterPatternBecomesTheUtestPatternDefine() throws ExecutionException {
    String path = fixturePath("test.hxml");
    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, "SampleTest.testPasses", false);
    assertTrue(plan.command().contains("UTEST_PATTERN=SampleTest.testPasses"),
               "filter define missing: " + plan.command());
  }

  @Test
  @DisplayName("hl build launches the bytecode artifact")
  public void testHlBuildLaunchesTheBytecodeArtifact() throws ExecutionException {
    String path = fixturePath("targets/hl.hxml");
    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertFalse(plan.singleStage(), "artifact targets compile in the before-run step");
    // HlExecutableResolver may find a full path (env/PATH-dependent) or fall
    // back to the bare name - either way the launched binary is hl
    String executableName = Path.of(plan.command().get(0)).getFileName().toString();
    assertTrue(executableName.equals("hl") || executableName.equals("hl.exe"),
               "hl launcher expected: " + plan.command());
    assertTrue(plan.command().get(1).endsWith("tests.hl"), "artifact path expected: " + plan.command());
  }

  @Test
  @DisplayName("jvm jar launches through java")
  public void testJvmJarLaunchesThroughJava() throws ExecutionException {
    String path = fixturePath("targets/jvm.hxml");
    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertEquals(HaxeSdkUtilBase.getExecutableName("java"), plan.command().get(0));
    assertEquals("-jar", plan.command().get(1));
    assertTrue(plan.command().get(2).endsWith("tests.jar"));
  }

  @Test
  @DisplayName("cpp executable is named after the main class")
  public void testCppExecutableIsNamedAfterTheMainClass() throws ExecutionException {
    String path = fixturePath("targets/cpp.hxml");
    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertEquals(1, plan.command().size());
    assertTrue(plan.command().get(0).endsWith(HaxeSdkUtilBase.getExecutableName("TestMain")),
               "hxcpp names the binary after the main class simple name: " + plan.command());
  }

  @Test
  @DisplayName("cpp binary flavor follows the compile arguments")
  public void testCppBinaryFlavorFollowsTheCompileArguments() throws ExecutionException {
    // hxcpp appends -debug to the binary when the build compiles with --debug;
    // the flavor is decided by the arguments, never by what sits on disk
    String debugBinary = HaxeSdkUtilBase.getExecutableName("TestMain-debug");

    Plan debugHxml = HaxeTestLaunchPlanner.plan(getProject(), fixturePath("targets/cpp-debug.hxml"), null, false);
    assertTrue(debugHxml.command().get(0).endsWith(debugBinary),
               "a --debug hxml produces the -debug binary even for plain runs: " + debugHxml.command());

    Plan debugExecutor = HaxeTestLaunchPlanner.planForDebug(getProject(), fixturePath("targets/cpp.hxml"), null);
    assertTrue(debugExecutor.command().get(0).endsWith(debugBinary),
               "the debug executor's compile additions rename the binary: " + debugExecutor.command());
  }

  @Test
  @DisplayName("js runs under node and hints when hxnodejs is absent")
  public void testJsRunsUnderNodeAndHintsWhenHxnodejsIsAbsent() throws ExecutionException {
    Plan withLib = HaxeTestLaunchPlanner.plan(getProject(), fixturePath("targets/js-node.hxml"), null, true);
    assertEquals(HaxeSdkUtilBase.getExecutableName("node"), withLib.command().get(0));
    assertNull(withLib.hint(), "hxnodejs declared - no hint");

    Plan plain = HaxeTestLaunchPlanner.plan(getProject(), fixturePath("targets/js-plain.hxml"), null, true);
    assertEquals(HaxeSdkUtilBase.getExecutableName("node"), plain.command().get(0));
    assertNotNull(plain.hint(), "plain js should carry the hxnodejs hint");
  }

  @Test
  @DisplayName("js without node on path is refused")
  public void testJsWithoutNodeOnPathIsRefused() {
    String path = fixturePath("targets/js-plain.hxml");
    assertThrows(ExecutionException.class, () -> HaxeTestLaunchPlanner.plan(getProject(), path, null, false));
  }

  @Test
  @DisplayName("non host runnable target is refused")
  public void testNonHostRunnableTargetIsRefused() {
    String path = fixturePath("targets/swf.hxml");
    assertThrows(ExecutionException.class, () -> HaxeTestLaunchPlanner.plan(getProject(), path, null, false));
  }

  @Test
  @DisplayName("chained hxml plans only its selected section")
  public void testChainedHxmlPlansOnlyItsSelectedSection() throws ExecutionException {
    String path = fixturePath("targets/chained.hxml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);

    // default selection is the first section (interp): a single compile-and-run
    // process built from the section's own lines - the file token is replaced,
    // so the appended reporting arguments belong to this section, and the
    // sibling hl build never compiles
    Plan interpPlan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertTrue(interpPlan.singleStage(), "first section is interp");
    assertTrue(interpPlan.command().contains("--interp"), "section arguments expected: " + interpPlan.command());
    assertTrue(interpPlan.command().containsAll(List.of("-lib", "utest")),
               "the --each block applies to every section: " + interpPlan.command());
    assertFalse(interpPlan.command().contains("chained.hxml"), "file token must be replaced: " + interpPlan.command());
    assertFalse(interpPlan.command().contains("-hl"), "sibling section must not compile: " + interpPlan.command());

    // selections are stored by section identity - inline sections carry the
    // build file's own name with an occurrence suffix
    HaxeSectionSelectionStore.getInstance(getProject()).setSelectedSection(file, "chained.hxml#2");
    Plan hlPlan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertFalse(hlPlan.singleStage(), "the hl section compiles in the before-run step");
    assertTrue(hlPlan.command().get(1).endsWith("tests.hl"), "artifact path expected: " + hlPlan.command());
  }

  @Test
  @DisplayName("single stage detection separates interp from artifact builds")
  public void testSingleStageDetectionSeparatesInterpFromArtifactBuilds() {
    assertTrue(HaxeTestLaunchPlanner.isSingleStage(getProject(), fixturePath("test.hxml")));
    assertFalse(HaxeTestLaunchPlanner.isSingleStage(getProject(), fixturePath("targets/hl.hxml")));
    assertTrue(HaxeTestLaunchPlanner.isSingleStage(getProject(), "missing/nowhere.hxml"),
               "unresolvable files get no compile step - checkConfiguration reports the problem");
  }

  @Test
  @DisplayName("compile arguments join activation filter suite name and reporter injection")
  public void testCompileArgumentsJoinActivationFilterSuiteNameAndReporterInjection() {
    // the suite-name define overrides utest's target #if chain, which lacks
    // several targets (HL runs would read "Target: Undefined")
    String interp = fixturePath("test.hxml");
    String arguments = HaxeTestLaunchPlanner.compileArguments(getProject(), interp, null);
    assertTrue(arguments.startsWith("-D teamcity -D \"teamcity_suite_name=Target: Interpretation\""),
               "activation and suite-name defines expected: " + arguments);
    assertTrue(arguments.contains("--macro intellij_utest.Macro.init()"),
               "the live reporter injection is on by default: " + arguments);

    String filtered = HaxeTestLaunchPlanner.compileArguments(getProject(), fixturePath("targets/hl.hxml"), "A.testX");
    assertTrue(filtered.startsWith("-D teamcity -D UTEST_PATTERN=A.testX -D \"teamcity_suite_name=Target: HashLink\""),
               "the filter define sits between activation and suite name: " + filtered);
  }

  @Test
  @DisplayName("lime tests build launches the packaged binary")
  public void testLimeTestsBuildLaunchesThePackagedBinary() throws ExecutionException {
    String path = fixturePath("targets/lime-project.xml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "Neko");

    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertFalse(plan.singleStage(), "lime builds compile in the before-run step");
    assertEquals(HaxeTarget.NEKO, plan.target());
    String binary = plan.command().get(0).replace('\\', '/');
    assertTrue(binary.endsWith("export/neko/bin/" + HaxeSdkUtilBase.getExecutableName("LimeTests")),
               "lime packages `<app path>/<target>/bin/<app file>`: " + plan.command());
  }

  @Test
  @DisplayName("lime compile arguments use the tool forwarding spelling")
  public void testLimeCompileArgumentsUseTheToolForwardingSpelling() {
    String path = fixturePath("targets/lime-project.xml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "Neko");

    String arguments = HaxeTestLaunchPlanner.compileArguments(getProject(), path, "A.testX");
    // ATTACHED defines: the two-word -D spelling trips a lime bug duplicating the value
    assertTrue(arguments.startsWith("-Dteamcity -DUTEST_PATTERN=A.testX \"-Dteamcity_suite_name=Target: Neko\""),
               "lime spelling expected: " + arguments);
    assertTrue(arguments.contains("--source="), "the reporter classpath rides lime's --source: " + arguments);
    assertTrue(arguments.contains("\"--haxeflag=--macro intellij_utest.Macro.init()\""),
               "the reporter macro rides lime's --haxeflag: " + arguments);
  }

  @Test
  @DisplayName("lime hl package exposes its boot bytecode for the debugger")
  public void testLimeHlPackageExposesItsBootBytecodeForTheDebugger() throws ExecutionException {
    String path = fixturePath("targets/lime-project.xml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "HashLink");

    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertEquals(HaxeTarget.HL, plan.target());
    assertNull(HaxeTestLaunchPlanner.hlArtifact(plan), "a lime package is not the bare [hl, artifact] shape");
    Path boot = HaxeTestLaunchPlanner.packagedHlBoot(plan);
    assertNotNull(boot, "the packaged runtime debugs its hlboot.dat");
    assertTrue(boot.toString().replace('\\', '/').endsWith("export/hl/bin/hlboot.dat"), boot.toString());
  }

  @Test
  @DisplayName("non host lime targets are rejected")
  public void testNonHostLimeTargetsAreRejected() {
    String path = fixturePath("targets/lime-project.xml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "HTML5");

    assertThrows(ExecutionException.class, () -> HaxeTestLaunchPlanner.plan(getProject(), path, null, false),
                 "html5 tests arrive with the browser/CDP work - until then the target is rejected");
  }

  @Test
  @DisplayName("nme tests build launches the packaged neko launcher")
  public void testNmeTestsBuildLaunchesThePackagedNekoLauncher() throws ExecutionException {
    String path = fixturePath("targets/tests.nmml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "Neko");

    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), path, null, false);
    assertFalse(plan.singleStage(), "nme builds compile in the before-run step");
    assertEquals(HaxeTarget.NEKO, plan.target());
    String binary = plan.command().get(0).replace('\\', '/');
    assertTrue(binary.contains("-neko/NmeTests/"),
               "nme packages `<root>/<host>-neko/<app>/<launcher>`: " + plan.command());
    assertTrue(binary.endsWith("/" + HaxeSdkUtilBase.getExecutableName("NmeTests")), binary);
  }

  @Test
  @DisplayName("nme compile arguments use the tool forwarding spelling")
  public void testNmeCompileArgumentsUseTheToolForwardingSpelling() {
    String path = fixturePath("targets/tests.nmml");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    HaxeTargetSelectionStore.getInstance(getProject()).setSelectedTargetId(file, "Neko");

    String arguments = HaxeTestLaunchPlanner.compileArguments(getProject(), path, null);
    assertTrue(arguments.startsWith("-Dteamcity \"-Dteamcity_suite_name=Target: Neko\""),
               "attached defines expected: " + arguments);
    // nme swallows single-dash haxe flags; the classpath must ride --class-path
    assertTrue(arguments.contains("\"--class-path "), "classpath spelling expected: " + arguments);
    assertTrue(arguments.contains("\"--macro intellij_utest.Macro.init()\""),
               "the reporter macro rides a double-dash token: " + arguments);
  }

  @Test
  @DisplayName("disabling live test reporting drops the injection")
  public void testDisablingLiveTestReportingDropsTheInjection() {
    HaxeBuildToolSettings settings = HaxeBuildToolSettings.getInstance(getProject());
    settings.setLiveTestReporting(false);
    try {
      assertEquals("-D teamcity -D \"teamcity_suite_name=Target: Interpretation\"",
                   HaxeTestLaunchPlanner.compileArguments(getProject(), fixturePath("test.hxml"), null));
    } finally {
      settings.setLiveTestReporting(true);
    }
  }
}
