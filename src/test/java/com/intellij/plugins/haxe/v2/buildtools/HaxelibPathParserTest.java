package com.intellij.plugins.haxe.v2.buildtools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Build tools: haxelib path parser")
public class HaxelibPathParserTest {

  @Test
  @DisplayName("bare lines are classpaths and flags are skipped")
  public void bareLinesAreClasspathsAndFlagsAreSkipped() {
    List<String> classpaths = HaxelibPathParser.parseClasspaths(List.of(
      "C:/HaxeToolkit/haxe/lib/lime/8,0,2/",
      "-D lime=8.0.2",
      "C:/HaxeToolkit/haxe/lib/openfl/9,2,2/",
      "-D openfl=9.2.2",
      "-L lime"));

    assertEquals(List.of("C:/HaxeToolkit/haxe/lib/lime/8,0,2/",
                         "C:/HaxeToolkit/haxe/lib/openfl/9,2,2/"),
                 classpaths);
  }

  @Test
  @DisplayName("error output yields no classpaths")
  public void errorOutputYieldsNoClasspaths() {
    List<String> classpaths = HaxelibPathParser.parseClasspaths(List.of(
      "Error: Library nosuchlib is not installed",
      ""));
    assertTrue(classpaths.isEmpty());
  }

  @Test
  @DisplayName("version comes from the lib's own -D marker, not a dependency's")
  public void versionComesFromTheLibsOwnDMarkerNotADependencys() {
    List<String> output = List.of(
      "C:/HaxeToolkit/haxe/lib/lime/8,0,2/",
      "-D lime=8.0.2",
      "C:/HaxeToolkit/haxe/lib/openfl/9,2,2/",
      "-D openfl=9.2.2");

    assertEquals("9.2.2", HaxelibPathParser.parseVersion("openfl", output));
    assertEquals("8.0.2", HaxelibPathParser.parseVersion("lime", output));
    assertNull(HaxelibPathParser.parseVersion("actuate", output));
  }
}
