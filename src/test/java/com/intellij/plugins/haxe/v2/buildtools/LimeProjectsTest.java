package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.util.SystemInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Build tools: lime projects")
public class LimeProjectsTest {

  @Test
  @DisplayName("app file defaults to lime's when the project xml declares none")
  public void testAppFileDefaultsToLimesWhenTheProjectXmlDeclaresNone() {
    assertEquals("MyApplication", LimeProjects.appFile("<project><app main=\"Tests\"/></project>"));
    assertEquals("Shapes", LimeProjects.appFile("<project><app main=\"Main\" file=\"Shapes\"/></project>"));
  }

  /** (target flag, app file as declared, expected output relative to the project file). */
  static final List<Arguments> OUTPUTS = List.of(
    arguments("neko", "", "bin/neko/bin/MyApplication" + (SystemInfo.isWindows ? ".exe" : "")),
    arguments("neko", "Shapes", "bin/neko/bin/Shapes" + (SystemInfo.isWindows ? ".exe" : "")),
    arguments("windows", "", "bin/windows/bin/MyApplication.exe"),
    arguments("linux", "", "bin/linux/bin/MyApplication"),
    arguments("html5", "", "bin/html5/bin/MyApplication.js"),
    arguments("flash", "", "bin/flash/bin/MyApplication.swf"),
    arguments("hl", "Shapes", "bin/hl/obj/ApplicationMain.hl"));

  @ParameterizedTest(name = "{0} / {1}")
  @FieldSource("OUTPUTS")
  public void testRelativeTargetOutput(String targetFlag, String appFile, String expected) {
    assertEquals(expected, LimeProjects.relativeTargetOutput(targetFlag, "bin", appFile));
  }
}
