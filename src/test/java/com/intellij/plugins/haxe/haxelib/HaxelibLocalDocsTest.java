package com.intellij.plugins.haxe.haxelib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Local repository layout resolution: comma version directories, the .dev pointer, and doc-file discovery. */
@DisplayName("Haxelib: local docs resolution")
public class HaxelibLocalDocsTest {

  @Test
  @DisplayName("release versions map dots to comma directories")
  public void testReleaseVersionsMapDotsToCommaDirectories() throws Exception {
    Path repo = Files.createTempDirectory("haxelib-repo");
    Path versionDir = Files.createDirectories(repo.resolve("lime/8,3,2"));

    assertEquals(versionDir, HaxelibLocalDocs.versionDirectory(repo, "lime", "8.3.2"));
    assertNull(HaxelibLocalDocs.versionDirectory(repo, "lime", "9.9.9"), "absent versions resolve to null");
  }

  @Test
  @DisplayName("dev versions follow the dev pointer file")
  public void testDevVersionsFollowTheDevPointerFile() throws Exception {
    Path repo = Files.createTempDirectory("haxelib-repo");
    Path devTarget = Files.createTempDirectory("haxelib-dev-target");
    Files.createDirectories(repo.resolve("mylib"));
    Files.writeString(repo.resolve("mylib/.dev"), devTarget + System.lineSeparator());

    assertEquals(devTarget, HaxelibLocalDocs.versionDirectory(repo, "mylib", "dev"));
  }

  @Test
  @DisplayName("doc files match well known names in display order regardless of case")
  public void testDocFilesMatchWellKnownNamesInDisplayOrderRegardlessOfCase() throws Exception {
    Path versionDir = Files.createTempDirectory("haxelib-version");
    Files.writeString(versionDir.resolve("CHANGELOG.md"), "changes");
    Files.writeString(versionDir.resolve("readme.MD".toLowerCase()), "readme");
    Files.writeString(versionDir.resolve("LICENSE"), "license");
    Files.writeString(versionDir.resolve("Notes.md"), "not a doc tab");

    List<Path> docs = HaxelibLocalDocs.docFiles(versionDir);
    List<String> names = docs.stream().map(p -> p.getFileName().toString()).toList();
    assertEquals(List.of("readme.md", "CHANGELOG.md", "LICENSE"), names,
                 "readme first, then changelog and license; unrelated markdown excluded");
  }
}
