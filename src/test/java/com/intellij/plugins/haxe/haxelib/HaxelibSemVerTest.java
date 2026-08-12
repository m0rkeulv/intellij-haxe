package com.intellij.plugins.haxe.haxelib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Haxelib: semantic version")
public class HaxelibSemVerTest {

  @Test
  @DisplayName("release versions accepted including pre-release suffixes")
  public void releaseVersionsAcceptedIncludingPreReleaseSuffixes() {
    assertTrue(HaxelibSemVer.isReleaseVersion("1.2.3"));
    assertTrue(HaxelibSemVer.isReleaseVersion("9.2.0-rc.1"));
    assertTrue(HaxelibSemVer.isReleaseVersion("1.0.0-alpha.0"));
    assertTrue(HaxelibSemVer.isReleaseVersion("1.2.3+build.5"));
  }

  @Test
  @DisplayName("create parses prerelease and build metadata")
  public void createParsesPrereleaseAndBuildMetadata() {
    assertEquals("9.2.0-rc.1", HaxelibSemVer.create("9.2.0-rc.1").toString());
    assertEquals("1.0.0-alpha.0+build.5", HaxelibSemVer.create("1.0.0-alpha.0+build.5").toString());
    assertNotEquals(HaxelibSemVer.create("1.0.0-rc.1"), HaxelibSemVer.create("1.0.0"));
    assertNotEquals(HaxelibSemVer.create("1.0.0-rc.1"), HaxelibSemVer.create("1.0.0-rc.2"));
    // build metadata is ignored for precedence, so it does not split identity
    assertEquals(HaxelibSemVer.create("1.0.0+b1"), HaxelibSemVer.create("1.0.0+b2"));
  }

  @Test
  @DisplayName("create accepts haxelib directory comma forms")
  public void createAcceptsHaxelibDirectoryCommaForms() {
    assertEquals(HaxelibSemVer.create("8.0.2"), HaxelibSemVer.create("8,0,2"));
    assertEquals(HaxelibSemVer.create("1.0.0-rc.1"), HaxelibSemVer.create("1,0,0-rc,1"));
    assertEquals("1,0,0-rc,1", HaxelibSemVer.create("1.0.0-rc.1").toDirString());
    assertEquals("8,0,2", HaxelibSemVer.create("8.0.2").toDirString());
  }

  @Test
  @DisplayName("create maps pseudo versions to constants and garbage to zero")
  public void createMapsPseudoVersionsToConstantsAndGarbageToZero() {
    assertSame(HaxelibSemVer.ANY_VERSION, HaxelibSemVer.create(null));
    assertSame(HaxelibSemVer.ANY_VERSION, HaxelibSemVer.create("any"));
    assertSame(HaxelibSemVer.GIT_VERSION, HaxelibSemVer.create("git"));
    assertSame(HaxelibSemVer.HG_VERSION, HaxelibSemVer.create("hg"));
    assertSame(HaxelibSemVer.DEVELOPMENT_VERSION, HaxelibSemVer.create("dev"));
    assertSame(HaxelibSemVer.ZERO_VERSION, HaxelibSemVer.create("not-a-version"));
  }

  @Test
  @DisplayName("pseudo versions partial versions and source specs rejected")
  public void pseudoVersionsPartialVersionsAndSourceSpecsRejected() {
    assertFalse(HaxelibSemVer.isReleaseVersion(null));
    assertFalse(HaxelibSemVer.isReleaseVersion(""));
    assertFalse(HaxelibSemVer.isReleaseVersion("any"));
    assertFalse(HaxelibSemVer.isReleaseVersion("git"));
    assertFalse(HaxelibSemVer.isReleaseVersion("hg"));
    assertFalse(HaxelibSemVer.isReleaseVersion("dev"));
    assertFalse(HaxelibSemVer.isReleaseVersion("8.0"));
    // comma form is haxelib's DIRECTORY naming, not a version argument
    assertFalse(HaxelibSemVer.isReleaseVersion("8,0,2"));
    assertFalse(HaxelibSemVer.isReleaseVersion("git:https://example.invalid/repo.git"));
  }
}
