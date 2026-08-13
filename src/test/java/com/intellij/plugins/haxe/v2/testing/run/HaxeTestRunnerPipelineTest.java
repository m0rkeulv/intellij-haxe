package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.TestProxyPrinterProvider;
import com.intellij.execution.testframework.sm.runner.events.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.v2.testing.run.HaxeTestLaunchPlanner.Plan;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The full vertical slice, live (self-skips without haxe on the PATH):
 * configuration -> plan -> real {@code haxe --interp} run -> the run's
 * converter (with locationHint injection) -> platform-parsed SM test events.
 * The first test uses a self-contained TeamCity-printing main mirroring
 * utest's BATCH reporter shapes (no haxelib dependency - this is the fallback
 * path when the live-reporter extraction fails); the second compiles against
 * real utest (self-skips when not installed) and proves the shipped live
 * reporter: streamed events and per-test output attribution.
 */
@DisplayName("Test runner: SM pipeline (live)")
public class HaxeTestRunnerPipelineTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/testing/runner/";
  }

  @Test
  @Timeout(120)
  @DisplayName("run produces parsed test events with locations")
  public void testRunProducesParsedTestEventsWithLocations() throws Exception {
    assumeTrue(haxeAvailable(), "haxe not on PATH - skipping live test-runner pipeline test");

    myFixture.copyFileToProject("src/TestMain.hx");
    VirtualFile buildFile = myFixture.copyFileToProject("test.hxml");

    HaxeTestRunConfiguration configuration = newConfiguration(buildFile.getPath());
    assertDoesNotThrow(configuration::checkConfiguration, "the interp fixture must validate");

    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), buildFile.getPath(), null);
    assertTrue(plan.singleStage(), "the fixture targets --interp");

    RecordingEventsProcessor recorder = runThroughConverter(configuration, plan, 0);

    assertTrue(recorder.startedSuites.contains("cases.SampleTest"),
               "suite event missing, got: " + recorder.startedSuites);
    assertTrue(recorder.startedTests.contains("cases.SampleTest.testPasses"),
               "passing test start missing, got: " + recorder.startedTests);
    assertTrue(recorder.failedTests.contains("cases.SampleTest.testFails"),
               "failing test event missing, got: " + recorder.failedTests);
    // utest emits no testFinished after a failure - the converter synthesizes it
    assertTrue(recorder.finishedTests.contains("cases.SampleTest.testFails"),
               "the failed test must still finish, got: " + recorder.finishedTests);
    assertEquals("haxe:test://cases.SampleTest.testPasses",
                 recorder.locationsByTest.get("cases.SampleTest.testPasses"),
                 "the converter must have injected the location hint");
  }

  @Test
  @Timeout(120)
  @DisplayName("live reporter streams events and the batch replay is deduplicated")
  public void testLiveReporterStreamsEventsAndTheBatchReplayIsDeduplicated() throws Exception {
    assumeTrue(haxeAvailable(), "haxe not on PATH - skipping real-utest pipeline test");
    assumeTrue(utestAvailable(), "utest haxelib not installed - skipping real-utest pipeline test");

    myFixture.copyDirectoryToProject("utest", "utest");
    VirtualFile buildFile = myFixture.findFileInTempDir("utest/test.hxml");
    assertNotNull(buildFile, "the utest fixture must be copied");

    Plan plan = HaxeTestLaunchPlanner.plan(getProject(), buildFile.getPath(), null);
    // utest exits 1: the assertion-less test counts as a warning and warnings make its stats not-ok
    RecordingEventsProcessor recorder = runThroughConverter(newConfiguration(buildFile.getPath()), plan, 1);

    // live names carry no leading dot (utest's BATCH spells the default
    // package as `.LiveCase` - those replays must all be swallowed)
    assertTrue(recorder.startedSuites.contains("LiveCase"), "class suite expected, got: " + recorder.startedSuites);
    assertEquals(List.of("LiveCase.testTraces", "LiveCase.testPasses", "LiveCase.testNoAsserts"),
                 recorder.finishedTests,
                 "each test finishes exactly once - the batch replay is deduplicated");
    assertFalse(recorder.startedSuites.contains(".LiveCase"),
                "the batch's suite replay must be swallowed, got: " + recorder.startedSuites);

    // output printed DURING a live-reported test arrives between its
    // started/finished events and attributes to it
    String tracedOutput = recorder.outputByTest.get("LiveCase.testTraces");
    assertNotNull(tracedOutput, "trace must be attributed to testTraces, got: " + recorder.outputByTest);
    assertTrue(tracedOutput.contains("hello from the traced test"), "trace text expected, got: " + tracedOutput);

    assertTrue(recorder.failedTests.contains("LiveCase.testNoAsserts"),
               "warnings-only test fails, as in the VSCode adapter");
    assertFalse(recorder.failedTests.contains("LiveCase.testPasses"),
                "the silent passing test must stay green");
  }

  @NotNull
  private HaxeTestRunConfiguration newConfiguration(@NotNull String buildFilePath) {
    HaxeTestRunConfigurationType type = new HaxeTestRunConfigurationType();
    HaxeTestRunConfiguration configuration =
      new HaxeTestRunConfiguration(getProject(), type.getFactory(), "pipeline");
    configuration.setBuildFilePath(buildFilePath);
    return configuration;
  }

  /** Runs the plan's process, streaming its output through the run's real converter into a recording processor. */
  @NotNull
  private RecordingEventsProcessor runThroughConverter(@NotNull HaxeTestRunConfiguration configuration,
                                                       @NotNull Plan plan,
                                                       int expectedExitCode) throws ExecutionException {
    HaxeTestConsoleProperties properties =
      new HaxeTestConsoleProperties(configuration, DefaultRunExecutor.getRunExecutorInstance());
    HaxeTestEventsConverter converter = new HaxeTestEventsConverter("HaxeUnitTests", properties);
    RecordingEventsProcessor recorder = new RecordingEventsProcessor();
    converter.setTestingStartedHandler(() -> { });
    converter.setProcessor(recorder);
    converter.startTesting();
    try {
      GeneralCommandLine commandLine = new GeneralCommandLine(plan.command())
        .withWorkDirectory(plan.workDirectory());
      CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
      handler.addProcessListener(new ProcessListener() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          converter.process(event.getText(), outputType);
        }
      });
      ProcessOutput output = handler.runProcess(90_000);
      assertFalse(output.isTimeout(), "the interp run must finish");
      assertEquals(expectedExitCode, output.getExitCode(),
                   "unexpected run outcome:\n" + output.getStdout() + output.getStderr());
      converter.flushBufferOnProcessTermination(output.getExitCode());
      return recorder;
    }
    finally {
      Disposer.dispose(properties);
    }
  }

  /** Captures the platform-parsed SM events; navigation urls prove the locationHint injection ran. */
  private final class RecordingEventsProcessor extends GeneralTestEventsProcessor {
    final List<String> startedSuites = new CopyOnWriteArrayList<>();
    final List<String> startedTests = new CopyOnWriteArrayList<>();
    final List<String> finishedTests = new CopyOnWriteArrayList<>();
    final List<String> failedTests = new CopyOnWriteArrayList<>();
    final Map<String, String> locationsByTest = new ConcurrentHashMap<>();
    /**
     * Output attributed to a test, keyed by that test - explicit testStdOut
     * events (the converter's batch-shape attribution) merged with uncaptured
     * output arriving between a test's started and finished (the live shape).
     */
    final Map<String, String> outputByTest = new ConcurrentHashMap<>();
    private volatile String runningTest;

    private RecordingEventsProcessor() {
      super(getProject(), "HaxeUnitTests", new SMTestProxy.SMRootTestProxy());
    }

    @Override
    public void onStartTesting() { }

    @Override
    public void onTestsCountInSuite(int count) { }

    @Override
    public void onTestStarted(@NotNull TestStartedEvent event) {
      startedTests.add(event.getName());
      runningTest = event.getName();
      if (event.getLocationUrl() != null) {
        locationsByTest.put(event.getName(), event.getLocationUrl());
      }
    }

    @Override
    public void onTestFinished(@NotNull TestFinishedEvent event) {
      finishedTests.add(event.getName());
      runningTest = null;
    }

    @Override
    public void onTestFailure(@NotNull TestFailedEvent event) {
      failedTests.add(event.getName());
    }

    @Override
    public void onTestIgnored(@NotNull TestIgnoredEvent event) { }

    @Override
    public void onTestOutput(@NotNull TestOutputEvent event) {
      outputByTest.merge(event.getName(), event.getText(), String::concat);
    }

    @Override
    public void onSuiteStarted(@NotNull TestSuiteStartedEvent event) {
      startedSuites.add(event.getName());
    }

    @Override
    public void onSuiteFinished(@NotNull TestSuiteFinishedEvent event) { }

    @Override
    public void onUncapturedOutput(@NotNull String text, Key outputType) {
      String current = runningTest;
      if (current != null) {
        outputByTest.merge(current, text, String::concat);
      }
    }

    @Override
    public void onError(@NotNull String localizedMessage, @Nullable String stackTrace, boolean isCritical) { }

    @Override
    public void onTestsReporterAttached() { }

    @Override
    public void setPrinterProvider(@NotNull TestProxyPrinterProvider printerProvider) { }
  }
}
