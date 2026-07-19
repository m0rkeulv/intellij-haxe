package com.intellij.plugins.haxe.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Debugger compatibility matrix: provisions the haxe/HashLink toolchains
 * into {@code <repo>/debuggerResources}, runs each debugger lane's test
 * suite per toolchain, and writes the HTML report. Entry point of
 * {@code gradlew debuggerCompatibilityReport}; see README.md.
 *
 * The lane mechanics encode hard-won lessons from the runs that certified
 * the version support policy — see the comments at the decision points and
 * in {@link Gradle} before "simplifying" any of them.
 */
public final class MatrixMain {
  private final Path root;
  private final Path resources;
  private final Path out;
  private final List<String> lanes;
  private final boolean full;
  private final Log log;
  private final Gradle gradle;
  private final List<Results.Cell> cells = new ArrayList<>();

  private List<Path> haxeDirs = List.of();
  private List<Path> hlDirs = List.of();

  private static final List<String> HL_FIXTURE_TASKS = List.of(
    "buildTestFixture", "buildThreadsFixture", "buildSpinFixture", "buildUncaughtFixture",
    "buildVmFixture", "buildStackTraceFixture", "buildTypedThrowFixture");
  private static final Map<String, String> HL_FIXTURE_FILES = Map.of(
    "buildTestFixture", "test-fixture.hl", "buildThreadsFixture", "threads-fixture.hl",
    "buildSpinFixture", "spin-fixture.hl", "buildUncaughtFixture", "uncaught-fixture.hl",
    "buildVmFixture", "vm-fixture.hl", "buildStackTraceFixture", "stacktrace-fixture.hl",
    "buildTypedThrowFixture", "typedthrow-fixture.hl");
  private static final List<String> EXCLUDE_HAXELIB = List.of(
    "-x", "installFormatHaxelib", "-x", "registerDapProtocolHaxelib",
    "-x", "registerServerHaxelib", "-x", "installHscript");

  private MatrixMain(Path root, Path resources, Path out, List<String> lanes, boolean full) throws IOException {
    this.root = root;
    this.resources = resources;
    this.out = out;
    this.lanes = lanes;
    this.full = full;
    this.log = new Log(out.resolve("progress.log"));
    this.gradle = new Gradle(root, log);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getProperty("matrix.root", ".")).toAbsolutePath().normalize();
    Path resources = root.resolve("debuggerResources");
    Path out = root.resolve("build/reports/debugger-matrix");
    List<String> lanes = new ArrayList<>(List.of("eval", "hashlink", "hxcpp"));
    boolean full = false;
    boolean reportOnly = false;
    for (String arg : args) {
      if (arg.startsWith("--lanes=")) {
        lanes = new ArrayList<>(Arrays.stream(arg.substring(8).split(","))
                                  .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList());
      } else if (arg.startsWith("--resources=")) {
        resources = Path.of(arg.substring(12)).toAbsolutePath().normalize();
      } else if (arg.startsWith("--out=")) {
        out = Path.of(arg.substring(6)).toAbsolutePath().normalize();
      } else if (arg.equals("--full")) {
        full = true;
      } else if (arg.equals("--report-only")) {
        reportOnly = true;
      } else {
        System.err.println("unknown argument: " + arg);
        System.exit(2);
      }
    }
    MatrixMain matrix = new MatrixMain(root, resources, out, lanes, full);
    if (reportOnly) {
      matrix.reportOnly();
    } else {
      matrix.run();
    }
  }

  private void run() throws IOException {
    Provisioner provisioner = new Provisioner(resources, log);
    haxeDirs = provisioner.haxeDirs();
    hlDirs = lanes.contains("hashlink") ? provisioner.hashlinkDirs() : List.of();
    log.line("matrix start: lanes=" + String.join("+", lanes)
             + " haxe=" + names(haxeDirs) + " hl=" + names(hlDirs));
    if (haxeDirs.isEmpty()) {
      log.line("no haxe toolchains available - aborting");
      System.exit(1);
    }

    if (lanes.contains("hashlink") || lanes.contains("hxcpp")) {
      // haxelib dev registrations ONCE, with the dev haxe; the lanes exclude
      // these tasks so nothing races the shared haxelib repository
      gradle.run(List.of(":debuggers:hashlink-debug-adapter:registerDapProtocolHaxelib",
                         ":debuggers:intellij-hxcpp-debugger:registerServerHaxelib"),
                 Map.of(), out.resolve("logs/preflight.log"), 300);
    }

    if (lanes.contains("eval")) {
      evalLane();
    }
    if (lanes.contains("hashlink")) {
      hashlinkLane();
    }
    if (lanes.contains("hxcpp")) {
      hxcppLane();
    }
    restore();
    report();
    log.line("matrix done");
  }

  // ------------------------------------------------------------------ lanes

  private Map<String, String> haxeEnv(Path haxeDir) {
    Path binDir = Platform.findBinary(haxeDir, "haxe").getParent();
    Map<String, String> env = new LinkedHashMap<>();
    env.put("PATH", binDir + java.io.File.pathSeparator + System.getenv("PATH"));
    env.put("HAXE_STD_PATH", binDir.resolve("std").toString());
    return env;
  }

  private void evalLane() throws IOException {
    log.line("EVAL LANE");
    for (Path haxeDir : haxeDirs) {
      String haxe = haxeDir.getFileName().toString();
      long start = System.nanoTime();
      Gradle.Status status = gradle.run(
        List.of(":debuggers:eval-debugger:cleanTest", ":debuggers:eval-debugger:test",
                "-PdebuggerTests=true", "--no-build-cache", "--continue"),
        haxeEnv(haxeDir), out.resolve("logs/eval-" + haxe + ".log"), 900);
      Gradle.killStrays();
      List<Results.ClassResult> classes = Results.collect(
        root.resolve("debuggers/eval-debugger/build/test-results/test"),
        out.resolve("results/eval_" + haxe));
      addCell("eval", haxe, null, status.name().toLowerCase(Locale.ROOT), classes, start);
    }
  }

  private void hxcppLane() throws IOException {
    log.line("HXCPP LANE");
    Map<String, String> fixtures = Map.of(
      "buildHxcppFixtureFixture", "hxcpp/fixture/" + Platform.exe("Main-debug"),
      "buildHxcppFixtureExFixture", "hxcpp/fixture-ex/" + Platform.exe("MainEx-debug"));
    Path moduleBuild = root.resolve("debuggers/intellij-hxcpp-debugger/build");
    for (Path haxeDir : haxeDirs) {
      String haxe = haxeDir.getFileName().toString();
      long start = System.nanoTime();
      deleteQuietly(moduleBuild.resolve("hxcpp"));
      List<String> build = new ArrayList<>();
      fixtures.keySet().stream().sorted().forEach(t -> build.add(":debuggers:intellij-hxcpp-debugger:" + t));
      build.addAll(EXCLUDE_HAXELIB);
      build.add("--continue");
      gradle.run(build, haxeEnv(haxeDir), out.resolve("logs/hxcpp-" + haxe + "-build.log"), 1200);
      List<String> missing = fixtures.entrySet().stream()
        .filter(e -> !Files.isRegularFile(moduleBuild.resolve(e.getValue())))
        .map(Map.Entry::getKey).toList();
      if (missing.size() == fixtures.size()) {
        addCell("hxcpp", haxe, null, "compile-fail", List.of(), start);
        continue;
      }
      List<String> test = new ArrayList<>(List.of(
        ":debuggers:intellij-hxcpp-debugger:cleanTest", ":debuggers:intellij-hxcpp-debugger:test",
        "--no-build-cache"));
      test.addAll(EXCLUDE_HAXELIB);
      missing.forEach(t -> test.addAll(List.of("-x", t)));
      test.add("--continue");
      Gradle.Status status = gradle.run(test, haxeEnv(haxeDir), out.resolve("logs/hxcpp-" + haxe + "-test.log"), 1500);
      Gradle.killStrays();
      List<Results.ClassResult> classes = Results.collect(
        moduleBuild.resolve("test-results/test"), out.resolve("results/hxcpp_" + haxe));
      addCell("hxcpp", haxe, null, status.name().toLowerCase(Locale.ROOT), classes, start);
    }
  }

  private void hashlinkLane() throws IOException {
    Path moduleBuild = root.resolve("debuggers/hashlink-debug-adapter/build");
    Path adapter = moduleBuild.resolve("hl/hl-debug-adapter.hl");
    if (!Files.isRegularFile(adapter)) {
      // the shipped adapter is a DEV-haxe artifact; build it once and PIN it
      gradle.run(List.of(":debuggers:hashlink-debug-adapter:buildDebugAdapter"),
                 Map.of(), out.resolve("logs/hl-adapter.log"), 600);
    }
    long pinned = lastModified(adapter);
    log.line("HL LANE (adapter pinned; " + (full ? "FULL grid" : "smart-reduced") + ")");
    for (Path haxeDir : haxeDirs) {
      String haxe = haxeDir.getFileName().toString();
      HL_FIXTURE_FILES.values().forEach(f -> deleteQuietly(moduleBuild.resolve("hl/" + f)));
      long start = System.nanoTime();
      List<String> build = new ArrayList<>();
      HL_FIXTURE_TASKS.forEach(t -> build.add(":debuggers:hashlink-debug-adapter:" + t));
      build.addAll(EXCLUDE_HAXELIB);
      build.add("--continue");
      gradle.run(build, haxeEnv(haxeDir), out.resolve("logs/hl-" + haxe + "-build.log"), 600);
      List<String> missing = HL_FIXTURE_TASKS.stream()
        .filter(t -> !Files.isRegularFile(moduleBuild.resolve("hl/" + HL_FIXTURE_FILES.get(t))))
        .toList();
      if (missing.contains("buildTestFixture")) {
        for (Path hlDir : hlDirs) {
          addCell("hashlink", haxe, hlDir.getFileName().toString(), "compile-fail", List.of(), start);
        }
        continue;
      }
      List<Path> runtimes = (full || !VersionManifest.DEGRADED_HAXE_ON_HL.contains(haxe))
        ? hlDirs
        : hlDirs.stream().filter(d -> d.getFileName().toString().equals(VersionManifest.REFERENCE_RUNTIME)).toList();
      for (Path hlDir : runtimes) {
        String runtime = hlDir.getFileName().toString();
        Path hlBinary = Platform.findBinary(hlDir, "hl");
        long cellStart = System.nanoTime();
        List<String> test = new ArrayList<>(List.of(
          ":debuggers:hashlink-debug-adapter:cleanTest", ":debuggers:hashlink-debug-adapter:test",
          "-PhashlinkBin=" + hlBinary, "--no-build-cache", "-x", "buildDebugAdapter"));
        HL_FIXTURE_TASKS.forEach(t -> test.addAll(List.of("-x", t)));
        test.addAll(EXCLUDE_HAXELIB);
        test.add("--continue");
        Map<String, String> env = haxeEnv(haxeDir);
        if (!Platform.WINDOWS) {
          // linux: hl finds libhl.so and the std .hdll libraries beside itself
          String previous = System.getenv("LD_LIBRARY_PATH");
          env.put("LD_LIBRARY_PATH", hlBinary.getParent() + (previous != null ? ":" + previous : ""));
        }
        Gradle.Status status = gradle.run(test, env, out.resolve("logs/hl-" + haxe + "-" + runtime + ".log"), 1500);
        Gradle.killStrays();
        List<Results.ClassResult> classes = Results.collect(
          moduleBuild.resolve("test-results/test"), out.resolve("results/hl_" + haxe + "__" + runtime));
        addCell("hashlink", haxe, runtime, status.name().toLowerCase(Locale.ROOT), classes, cellStart);
      }
      if (lastModified(adapter) != pinned) {
        log.line("  WARNING: pinned adapter was rebuilt during " + haxe);
      }
    }
  }

  /** Dev haxe back in charge: rebuild the HL fixtures the lanes overwrote. */
  private void restore() {
    if (!lanes.contains("hashlink")) {
      return;
    }
    Path moduleBuild = root.resolve("debuggers/hashlink-debug-adapter/build");
    HL_FIXTURE_FILES.values().forEach(f -> deleteQuietly(moduleBuild.resolve("hl/" + f)));
    List<String> build = new ArrayList<>();
    HL_FIXTURE_TASKS.forEach(t -> build.add(":debuggers:hashlink-debug-adapter:" + t));
    build.addAll(EXCLUDE_HAXELIB);
    build.add("--continue");
    gradle.run(build, Map.of(), out.resolve("logs/restore-hl-fixtures.log"), 600);
  }

  // ----------------------------------------------------------------- report

  private void reportOnly() throws IOException {
    Path results = out.resolve("results");
    if (Files.isDirectory(results)) {
      try (var dirs = Files.list(results)) {
        for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
          String name = dir.getFileName().toString();
          String lane;
          String haxe;
          String runtime = null;
          if (name.startsWith("hl_")) {
            lane = "hashlink";
            String rest = name.substring(3);
            int split = rest.indexOf("__");
            haxe = split >= 0 ? rest.substring(0, split) : rest;
            runtime = split >= 0 ? rest.substring(split + 2) : null;
          } else {
            int split = name.indexOf('_');
            lane = name.substring(0, split);
            haxe = name.substring(split + 1);
          }
          cells.add(new Results.Cell(lane, haxe, runtime, "ok", Results.parse(dir), 0));
        }
      }
    }
    haxeDirs = List.of();
    hlDirs = List.of();
    report();
  }

  private void report() throws IOException {
    List<String> haxeNames = !haxeDirs.isEmpty() ? names(haxeDirs)
      : cells.stream().map(Results.Cell::haxe).distinct().sorted().toList();
    List<String> hlNames = !hlDirs.isEmpty() ? names(hlDirs)
      : cells.stream().map(Results.Cell::runtime).filter(r -> r != null).distinct().sorted().toList();
    Report.write(root.resolve("debuggers/compat-matrix/report-template.html"),
                 out.resolve("index.html"), cells, haxeNames, hlNames, resources, full);
    log.line("report: " + out.resolve("index.html"));
  }

  // ---------------------------------------------------------------- helpers

  private void addCell(String lane, String haxe, String runtime, String status,
                       List<Results.ClassResult> classes, long startNanos) {
    long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
    Results.Cell cell = new Results.Cell(lane, haxe, runtime, status, classes, seconds);
    cells.add(cell);
    log.line(String.format("  %s %s%s : %s classes=%d failures=%d (%ds)",
                           lane, haxe, runtime != null ? " x " + runtime : "", status,
                           classes.size(), cell.totalFailures(), seconds));
  }

  private static List<String> names(List<Path> dirs) {
    return dirs.stream().map(d -> d.getFileName().toString()).toList();
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (var walk = Files.walk(path)) {
          for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      } else {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
    }
  }
}
