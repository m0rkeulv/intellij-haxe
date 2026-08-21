package com.intellij.plugins.haxe.haxelib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Haxelib: git version spec")
public class HaxelibGitSpecTest {

  /** (pinned version string, expected clone url, expected ref). */
  static final List<Arguments> GIT_SPECS = List.of(
    arguments("git:https://example.com/repo.git", "https://example.com/repo.git", null),
    arguments("git:https://example.com/repo.git#main", "https://example.com/repo.git", "main"),
    arguments("git:https://example.com/repo.git#v1.2.0", "https://example.com/repo.git", "v1.2.0"),
    arguments("git:https://example.com/repo.git#0f3ab12c", "https://example.com/repo.git", "0f3ab12c"),
    // a trailing '#' pins nothing
    arguments("git:https://example.com/repo.git#", "https://example.com/repo.git", null));

  @ParameterizedTest(name = "{0}")
  @FieldSource("GIT_SPECS")
  @DisplayName("parses url and optional ref")
  public void parsesUrlAndOptionalRef(String version, String url, String ref) {
    HaxelibGitSpec spec = HaxelibGitSpec.parse(version);

    assertEquals(new HaxelibGitSpec(url, ref), spec);
  }

  @Test
  @DisplayName("non git versions parse to null")
  public void nonGitVersionsParseToNull() {
    assertNull(HaxelibGitSpec.parse(null));
    assertNull(HaxelibGitSpec.parse("1.2.0"));
    assertNull(HaxelibGitSpec.parse("dev"));
    assertNull(HaxelibGitSpec.parse("git"));
    // a spec without a clone url is unusable
    assertNull(HaxelibGitSpec.parse("git:"));
    assertNull(HaxelibGitSpec.parse("git:#main"));
  }

  @Test
  @DisplayName("short ref abbreviates commit hashes only")
  public void shortRefAbbreviatesCommitHashesOnly() {
    assertEquals("559b24c9a3", HaxelibGitSpec.shortRef("559b24c9a36533281ba7a2eed8aab83ed6b872b4"));
    assertEquals("main", HaxelibGitSpec.shortRef("main"));
    assertEquals("v1.2.0", HaxelibGitSpec.shortRef("v1.2.0"));
    // short hex names stay as written - they may be abbreviations already
    assertEquals("559b24c9a3", HaxelibGitSpec.shortRef("559b24c9a3"));
  }
}
