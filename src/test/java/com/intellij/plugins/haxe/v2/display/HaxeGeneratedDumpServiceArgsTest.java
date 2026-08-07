package com.intellij.plugins.haxe.v2.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Compiler services: generated dump build arguments")
public class HaxeGeneratedDumpServiceArgsTest {

  private static final Path DUMP_ROOT = Path.of("dump-root");

  @Test
  @DisplayName("redirects the output and appends the dump defines")
  public void testRedirectsTheOutputAndAppendsTheDumpDefines() {
    HaxeGeneratedDumpService.DumpBuild build = HaxeGeneratedDumpService.dumpArgs(List.of("-main", "Main", "-js", "bin/out.js"), DUMP_ROOT);
    List<String> args = build.args();

    assertFalse(args.contains("bin/out.js"), "the user's output path must never be written by a dump build");
    int jsFlag = args.indexOf("-js");
    assertTrue(args.get(jsFlag + 1).startsWith(DUMP_ROOT.toString()), "output redirected into the dump root");
    assertTrue(args.contains("dump=pretty"), "dump mode requested");
    assertTrue(args.contains("no-compilation"), "native compilation suppressed");
  }

  @Test
  @DisplayName("directory targets redirect to a directory path")
  public void testDirectoryTargetsRedirectToADirectoryPath() {
    List<String> args = HaxeGeneratedDumpService.dumpArgs(List.of("-main", "Main", "-cpp", "out/cpp"), DUMP_ROOT).args();

    int cppFlag = args.indexOf("-cpp");
    String redirected = args.get(cppFlag + 1);
    assertTrue(redirected.startsWith(DUMP_ROOT.toString()), "output redirected into the dump root");
    assertFalse(redirected.endsWith(".exe"), "a directory target gets a directory, not a file name");
  }

  @Test
  @DisplayName("post-build cmd steps are dropped")
  public void testPostBuildCmdStepsAreDropped() {
    List<String> args = HaxeGeneratedDumpService.dumpArgs(
      List.of("-main", "Main", "-js", "out.js", "-cmd", "echo done"), DUMP_ROOT).args();

    assertFalse(args.contains("-cmd"), "a dump build must run no post-build commands");
    assertFalse(args.contains("echo done"), "the command argument goes with its flag");
  }

  @Test
  @DisplayName("no-output is stripped so the generation pass runs")
  public void testNoOutputIsStrippedSoTheGenerationPassRuns() {
    List<String> args = HaxeGeneratedDumpService.dumpArgs(
      List.of("-main", "Main", "-js", "_", "--no-output"), DUMP_ROOT).args();

    assertFalse(args.contains("--no-output"), "lime display hxml disables generation; dumps need it");
  }

  @Test
  @DisplayName("run shapes are refused")
  public void testRunShapesAreRefused() {
    assertNull(HaxeGeneratedDumpService.dumpArgs(List.of("-x", "Main"), DUMP_ROOT), "-x executes the program");
    assertNull(HaxeGeneratedDumpService.dumpArgs(List.of("--run", "Main"), DUMP_ROOT), "--run executes the program");
  }

  @Test
  @DisplayName("target-less arg sets are refused")
  public void testTargetLessArgSetsAreRefused() {
    assertNull(HaxeGeneratedDumpService.dumpArgs(List.of("-main", "Main", "--interp"), DUMP_ROOT),
               "no generation pass means no dumps");
  }

  @Test
  @DisplayName("hxml file references expand against the preceding cwd")
  public void testHxmlFileReferencesExpandAgainstThePrecedingCwd(@TempDir Path projectDir) throws IOException {
    Files.writeString(projectDir.resolve("build.hxml"), """
      -cp src
      -main Main
      # output
      -js build/out.js
      """);

    List<String> expanded = HaxeGeneratedDumpService.expandHxmlReferences(
      List.of("--cwd", projectDir.toString(), "build.hxml"));

    assertFalse(expanded.contains("build.hxml"), "the reference is replaced by the file's flags");
    assertTrue(expanded.containsAll(List.of("-cp", "src", "-main", "Main", "-js", "build/out.js")),
               "flags read out of the referenced file");
    assertTrue(expanded.indexOf("--cwd") == 0, "the cwd pair stays in place");
  }

  @Test
  @DisplayName("unreadable hxml reference is kept as-is")
  public void testUnreadableHxmlReferenceIsKeptAsIs() {
    List<String> expanded = HaxeGeneratedDumpService.expandHxmlReferences(List.of("missing.hxml", "-main", "Main"));
    assertTrue(expanded.contains("missing.hxml"), "expansion failure leaves the argument untouched");
  }
}
