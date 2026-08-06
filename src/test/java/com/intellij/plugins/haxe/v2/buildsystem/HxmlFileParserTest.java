package com.intellij.plugins.haxe.v2.buildsystem;

import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeDefine;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Build system: hxml file parser")
public class HxmlFileParserTest {

  private static final HxmlFileParser.IncludeResolver NO_INCLUDES = path -> null;

  @Test
  @DisplayName("parses target defines and libraries")
  public void parsesTargetDefinesAndLibraries() {
    HaxeBuildFileInfo info = HxmlFileParser.parse("""
      # build config
      -cp src
      -main Main
      -lib openfl:9.2.0
      -lib format
      -D analyzer-optimize
      -D myvalue=42
      -js bin/app.js
      """, NO_INCLUDES);

    assertEquals(HaxeTarget.JAVA_SCRIPT, info.target());
    assertEquals(List.of(new HaxeDefine("analyzer-optimize", null), new HaxeDefine("myvalue", "42")), info.defines());
    assertEquals(List.of(new HaxeLibDependency("openfl", "9.2.0"), new HaxeLibDependency("format", null)), info.libraries());
    assertEquals(List.of("src"), info.classpaths());
  }

  @Test
  @DisplayName("collects classpaths from every flag spelling")
  public void collectsClasspathsFromEveryFlagSpelling() {
    HaxeBuildFileInfo info = HxmlFileParser.parse("""
      -cp src
      -p C:/HaxeToolkit/haxe/lib/lime/8,1,2/src
      --class-path vendored/format
      -hl bin/app.hl
      """, NO_INCLUDES);

    assertEquals(List.of("src", "C:/HaxeToolkit/haxe/lib/lime/8,1,2/src", "vendored/format"), info.classpaths());
  }

  @Test
  @DisplayName("recognizes double dash and alias target flags")
  public void recognizesDoubleDashAndAliasTargetFlags() {
    Map<String, HaxeTarget> expectations = Map.of(
      "--hl out.hl", HaxeTarget.HL,
      "--jvm out.jar", HaxeTarget.JAVA,
      "-cpp out", HaxeTarget.CPP,
      "--interp", HaxeTarget.INTERP,
      "-as3 out", HaxeTarget.FLASH);
    expectations.forEach((line, target) ->
      assertEquals(target, HxmlFileParser.parse(line, NO_INCLUDES).target(), line));
  }

  @Test
  @DisplayName("first target flag wins")
  public void firstTargetFlagWins() {
    HaxeBuildFileInfo info = HxmlFileParser.parse("""
      -js bin/app.js
      --next
      -hl bin/app.hl
      """, NO_INCLUDES);
    assertEquals(HaxeTarget.JAVA_SCRIPT, info.target());
  }

  @Test
  @DisplayName("follows hxml includes")
  public void followsHxmlIncludes() {
    HxmlFileParser.IncludeResolver resolver = path ->
      path.equals("common.hxml") ? "-lib heaps\n-D common-define" : null;

    HaxeBuildFileInfo info = HxmlFileParser.parse("""
      common.hxml
      -hl bin/app.hl
      """, resolver);

    assertEquals(HaxeTarget.HL, info.target());
    assertEquals(List.of(new HaxeDefine("common-define", null)), info.defines());
    assertEquals(List.of(new HaxeLibDependency("heaps", null)), info.libraries());
  }

  @Test
  @DisplayName("include cycles do not recurse forever")
  public void includeCyclesDoNotRecurseForever() {
    HxmlFileParser.IncludeResolver resolver = path -> "self.hxml\n-lib once";
    HaxeBuildFileInfo info = HxmlFileParser.parse("self.hxml", resolver);
    assertEquals(List.of(new HaxeLibDependency("once", null)), info.libraries());
  }

  @Test
  @DisplayName("no target yields null")
  public void noTargetYieldsNull() {
    HaxeBuildFileInfo info = HxmlFileParser.parse("-cp src\n-main Main", NO_INCLUDES);
    assertNull(info.target());
    assertTrue(info.defines().isEmpty());
    assertTrue(info.libraries().isEmpty());
  }

  @Test
  @DisplayName("git library versions keep the full suffix")
  public void gitLibraryVersionsKeepTheFullSuffix() {
    HaxeBuildFileInfo info = HxmlFileParser.parse("-lib mylib:git:https://example.com/repo.git", NO_INCLUDES);
    assertEquals(List.of(new HaxeLibDependency("mylib", "git:https://example.com/repo.git")), info.libraries());
  }
}
