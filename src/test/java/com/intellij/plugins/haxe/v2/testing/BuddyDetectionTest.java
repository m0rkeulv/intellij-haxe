package com.intellij.plugins.haxe.v2.testing;

import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures use a stub {@code buddy.BuddySuite}/{@code buddy.SingleSuite}
 * chain, so detection is exercised over a transitive extends, not a direct
 * one.
 */
@DisplayName("Test detection: buddy")
public class BuddyDetectionTest extends HaxeTestFrameworkDetectionTestBase {

  private final BuddyFramework framework = new BuddyFramework();

  @Override
  protected String getBasePath() {
    return "/testing/";
  }

  private void configureFixtureProject() {
    myFixture.configureByFiles("buddy/BuddySuite.hx", "buddy/SingleSuite.hx",
                               "cases/BuddyStyleSuite.hx", "cases/NotATest.hx");
  }

  @Test
  @DisplayName("suite through single suite is detected")
  public void testSuiteThroughSingleSuiteIsDetected() {
    configureFixtureProject();
    assertTrue(framework.isTestClass(classByQName("cases.BuddyStyleSuite")));
  }

  @Test
  @DisplayName("plain class is not detected")
  public void testPlainClassIsNotDetected() {
    configureFixtureProject();
    assertFalse(framework.isTestClass(classByQName("cases.NotATest")));
  }

  @Test
  @DisplayName("reporting args ride the reporter define or nothing")
  public void testReportingArgsRideTheReporterDefineOrNothing() {
    assertEquals(List.of("-D", "teamcity_suite_name=Target: Interpretation",
                         "-D", "reporter=intellij_buddy.TcReporter",
                         "-cp", "reporter-root"),
                 framework.reportingArgs("Target: Interpretation", "reporter-root", true));
    // buddy has no TeamCity reporter of its own: the shipped one is the
    // result channel, so the live-reporting toggle does not drop it
    assertEquals(framework.reportingArgs("Target: Interpretation", "reporter-root", true),
                 framework.reportingArgs("Target: Interpretation", "reporter-root", false));
    assertTrue(framework.reportingArgs("Target: Interpretation", null, true).isEmpty(),
               "without the extracted reporter the run stays console-only");
    assertTrue(framework.filterArgs("Any.pattern").isEmpty(), "buddy has no filter define");
  }

  @Test
  @DisplayName("spec location resolves to the description literal in its file")
  public void testSpecLocationResolvesToTheDescriptionLiteralInItsFile() {
    configureFixtureProject();
    PsiElement resolved = framework.resolveTestLocation(getProject(), GlobalSearchScope.allScope(getProject()),
                                                        "haxe:buddy", "cases/BuddyStyleSuite.hx::adds numbers");
    assertNotNull(resolved, "the spec's file and description must resolve");
    assertEquals("BuddyStyleSuite.hx", resolved.getContainingFile().getName());
    assertTrue(resolved.getText().contains("adds numbers"),
               "navigation lands on the description literal, got: " + resolved.getText());

    assertNull(framework.resolveTestLocation(getProject(), GlobalSearchScope.allScope(getProject()),
                                             "haxe:test", "cases.BuddyStyleSuite"),
               "buddy answers only its own protocol");
  }
}
