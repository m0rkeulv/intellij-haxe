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
  @DisplayName("sections attribute each classpath to its own library")
  public void sectionsAttributeEachClasspathToItsOwnLibrary() {
    // verbatim `haxelib path hexannotation` output shape: requested lib
    // first, transitive dependencies after, each closed by its -D marker
    List<HaxelibPathParser.LibrarySection> sections = HaxelibPathParser.parseSections("hexannotation", List.of(
      "C:\\HaxeToolkit\\haxe\\lib\\hexannotation/0,35,0/src/",
      "-D hexannotation=0.35.0",
      "C:\\HaxeToolkit\\haxe\\lib\\tink_macro/0,16,1/src/",
      "-D tink_macro=0.16.1",
      "C:\\HaxeToolkit\\haxe\\lib\\tink_core/2,1,1/src/",
      "-D tink_core=2.1.1",
      "-L lime"));

    assertEquals(3, sections.size());
    assertEquals("hexannotation", sections.get(0).name());
    assertEquals("0.35.0", sections.get(0).version());
    assertEquals(List.of("C:\\HaxeToolkit\\haxe\\lib\\hexannotation/0,35,0/src/"), sections.get(0).classpaths());
    assertEquals("tink_core", sections.get(2).name());
    assertEquals(List.of("C:\\HaxeToolkit\\haxe\\lib\\tink_core/2,1,1/src/"), sections.get(2).classpaths());
  }

  @Test
  @DisplayName("trailing classpaths without a marker fall to the requested lib")
  public void trailingClasspathsWithoutAMarkerFallToTheRequestedLib() {
    List<HaxelibPathParser.LibrarySection> sections = HaxelibPathParser.parseSections("mylib", List.of(
      "C:/HaxeToolkit/haxe/lib/tink_core/2,1,1/src/",
      "-D tink_core=2.1.1",
      "C:/dev/mylib/extra-src/"));

    assertEquals(2, sections.size());
    assertEquals("mylib", sections.get(1).name());
    assertNull(sections.get(1).version());
    assertEquals(List.of("C:/dev/mylib/extra-src/"), sections.get(1).classpaths());
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
