package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.plugins.haxe.config.HaxeTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Build tools: NME projects")
public class NmeProjectsTest {

  @Test
  @DisplayName("windows target maps to the packaged exe")
  public void testWindowsTargetMapsToThePackagedExe() {
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact("windows", "MyGame", "bin");
    assertEquals(HaxeTarget.CPP, artifact.target());
    assertEquals("bin/windows/MyGame/MyGame.exe", artifact.relativeOutput());
  }

  @Test
  @DisplayName("the nmml app path overrides the default output root")
  public void testTheNmmlAppPathOverridesTheDefaultOutputRoot() {
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact("windows", "NyanCat", "Export");
    assertEquals("Export/windows/NyanCat/NyanCat.exe", artifact.relativeOutput());
  }

  @Test
  @DisplayName("cpp target maps to the host desktop build")
  public void testCppTargetMapsToTheHostDesktopBuild() {
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact("cpp", "MyGame", "bin");
    assertEquals(HaxeTarget.CPP, artifact.target());
    NmeProjects.TargetArtifact host = SystemInfo.isWindows ? NmeProjects.targetArtifact("windows", "MyGame", "bin")
                                     : SystemInfo.isMac ? NmeProjects.targetArtifact("mac", "MyGame", "bin")
                                     : NmeProjects.targetArtifact("linux", "MyGame", "bin");
    assertEquals(host.relativeOutput(), artifact.relativeOutput());
  }

  @Test
  @DisplayName("mac target maps into the app bundle")
  public void testMacTargetMapsIntoTheAppBundle() {
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact("mac", "MyGame", "bin");
    assertEquals(HaxeTarget.CPP, artifact.target());
    assertEquals("bin/mac64/MyGame.app/Contents/MacOS/MyGame", artifact.relativeOutput());
  }

  @Test
  @DisplayName("flash target maps to the swf")
  public void testFlashTargetMapsToTheSwf() {
    NmeProjects.TargetArtifact artifact = NmeProjects.targetArtifact("flash", "MyGame", "bin");
    assertEquals(HaxeTarget.FLASH, artifact.target());
    assertEquals("bin/flash/MyGame/MyGame.swf", artifact.relativeOutput());
  }

  @Test
  @DisplayName("target flags without a launchable artifact report none")
  public void testTargetFlagsWithoutALaunchableArtifactReportNone() {
    assertNull(NmeProjects.targetArtifact("neko", "MyGame", "bin"));
    assertNull(NmeProjects.targetArtifact("html5", "MyGame", "bin"));
    assertNull(NmeProjects.targetArtifact("android", "MyGame", "bin"));
    assertNull(NmeProjects.targetArtifact("ps4", "MyGame", "bin"));
  }
}
