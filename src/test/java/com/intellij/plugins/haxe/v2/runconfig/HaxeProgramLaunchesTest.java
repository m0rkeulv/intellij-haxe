package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The launch-kind gate: which build outputs get a Build &amp; run action. Uses
 * the registered configuration factories, so a kind's display name doubles as
 * a registration check.
 */
@DisplayName("Run configurations: program launches")
public class HaxeProgramLaunchesTest extends HaxeCodeInsightFixtureTestCase {

  @Override
  protected String getBasePath() {
    return "/testing/runner/";
  }

  private static HaxeBuildFileInfo info(HaxeTarget target, String output) {
    return new HaxeBuildFileInfo(target, output, List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("neko builds launch for every build file kind")
  public void testNekoBuildsLaunchForEveryBuildFileKind() {
    assertEquals("Neko Application",
                 HaxeProgramLaunches.launchKind(info(HaxeTarget.NEKO, "export/neko/obj/ApplicationMain.n"),
                                                HaxeBuildFileType.OPENFL));
    assertEquals("Neko Application",
                 HaxeProgramLaunches.launchKind(info(HaxeTarget.NEKO, "bin/windows-neko/App/App.exe"),
                                                HaxeBuildFileType.NMML));
    assertEquals("Neko Application",
                 HaxeProgramLaunches.launchKind(info(HaxeTarget.NEKO, "bin/tests.n"), HaxeBuildFileType.HXML));
  }

  @Test
  @DisplayName("established launch kinds stay mapped")
  public void testEstablishedLaunchKindsStayMapped() {
    assertNotNull(HaxeProgramLaunches.launchKind(info(HaxeTarget.HL, "bin/app.hl"), HaxeBuildFileType.HXML));
    assertNotNull(HaxeProgramLaunches.launchKind(info(HaxeTarget.CPP, "export"), HaxeBuildFileType.OPENFL));
    assertNull(HaxeProgramLaunches.launchKind(info(HaxeTarget.CPP, "bin/cpp"), HaxeBuildFileType.HXML),
               "plain hxml cpp launches through the tests/debug flows, not a Build & run action");
  }
}
