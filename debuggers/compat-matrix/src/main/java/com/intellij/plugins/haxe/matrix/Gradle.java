package com.intellij.plugins.haxe.matrix;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a child gradle build with a lane's environment. Always {@code
 * --no-daemon}: the forked test JVM must inherit THIS invocation's
 * PATH/HAXE_STD_PATH, and a warm daemon keeps the environment it was born
 * with (it would silently test the wrong haxe — live-observed). Bounded; on
 * timeout the whole process TREE dies, plus known stray debuggees — a stuck
 * runtime error dialog must not wedge the matrix.
 */
final class Gradle {
  private static final List<String> STRAY_BASENAMES =
    List.of("hl", "hl.exe", "haxe", "haxe.exe", "Main-debug", "Main-debug.exe", "MainEx-debug", "MainEx-debug.exe");

  private final Path root;
  private final Log log;

  Gradle(Path root, Log log) {
    this.root = root;
    this.log = log;
  }

  enum Status { OK, FAIL, TIMEOUT }

  Status run(List<String> tasks, Map<String, String> extraEnv, Path logFile, int timeoutSec) {
    return run(tasks, extraEnv, logFile, timeoutSec, false);
  }

  /**
   * With {@code liveTestProgress}, the child build gets the tool's init
   * script (per-test events on stdout — gradle only writes junit XMLs at
   * task END, so those cannot drive live progress) and the log file is
   * tailed while the build runs: each finished suite is logged with its
   * counts, each failing test immediately.
   */
  Status run(List<String> tasks, Map<String, String> extraEnv, Path logFile, int timeoutSec, boolean liveTestProgress) {
    List<String> command = new ArrayList<>();
    command.add(root.resolve(Platform.gradlew()).toString());
    command.addAll(tasks);
    if (liveTestProgress) {
      command.add("-I");
      command.add(root.resolve("debuggers/compat-matrix/test-events.init.gradle").toString());
    }
    command.add("--no-daemon");
    command.add("--console=plain");
    ProcessBuilder builder = new ProcessBuilder(command)
      .directory(root.toFile())
      .redirectOutput(logFile.toFile())
      .redirectError(new File(logFile + ".err"));
    builder.environment().putAll(extraEnv);
    try {
      Process process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
      TestEventTail tail = liveTestProgress ? new TestEventTail(logFile, log) : null;
      while (!process.waitFor(2, TimeUnit.SECONDS)) {
        if (tail != null) {
          tail.poll();
        }
        if (System.nanoTime() > deadline) {
          killTree(process.toHandle());
          killStrays();
          return Status.TIMEOUT;
        }
      }
      if (tail != null) {
        tail.poll();
        tail.flush();
      }
      return process.exitValue() == 0 ? Status.OK : Status.FAIL;
    } catch (IOException e) {
      log.line("gradle launch failed: " + e.getMessage());
      return Status.FAIL;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Status.FAIL;
    }
  }

  private static void killTree(ProcessHandle handle) {
    handle.descendants().forEach(ProcessHandle::destroyForcibly);
    handle.destroyForcibly();
  }

  /** Kills leftover debuggee/toolchain processes by exact executable name. */
  static void killStrays() {
    ProcessHandle.allProcesses().forEach(handle -> {
      String cmd = handle.info().command().orElse("");
      if (cmd.isEmpty()) {
        return;
      }
      String base = cmd.substring(Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\')) + 1);
      if (STRAY_BASENAMES.contains(base)) {
        handle.destroyForcibly();
      }
    });
  }
}
