package com.intellij.plugins.haxe.matrix;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tails a child gradle build's console log for the per-test events enabled
 * by test-events.init.gradle ("com.x.FooTest > barTest PASSED") and turns
 * them into live progress: one line per finished SUITE with its counts, and
 * an immediate line for every failing test. Byte-offset based so each poll
 * only reads what is new; a trailing partial line waits for the next poll.
 */
final class TestEventTail {
  private static final Pattern EVENT =
    Pattern.compile("^(\\S+) > (\\S+).* (PASSED|FAILED|SKIPPED)\\s*$");

  private final Path logFile;
  private final Log log;
  private long offset;
  private String carry = "";
  private String currentSuite;
  private int tests;
  private int failed;
  private int skipped;

  TestEventTail(Path logFile, Log log) {
    this.logFile = logFile;
    this.log = log;
  }

  void poll() {
    if (!Files.isRegularFile(logFile)) {
      return;
    }
    try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
      long length = file.length();
      if (length <= offset) {
        return;
      }
      file.seek(offset);
      byte[] chunk = new byte[(int)Math.min(length - offset, 1 << 20)];
      int read = file.read(chunk);
      offset += Math.max(read, 0);
      String text = carry + new String(chunk, 0, Math.max(read, 0), StandardCharsets.UTF_8);
      int lastNewline = text.lastIndexOf('\n');
      if (lastNewline < 0) {
        carry = text;
        return;
      }
      carry = text.substring(lastNewline + 1);
      for (String line : text.substring(0, lastNewline).split("\r?\n")) {
        handle(line);
      }
    } catch (IOException ignored) {
      // the log file being briefly unavailable only delays progress lines
    }
  }

  private void handle(String line) {
    Matcher matcher = EVENT.matcher(line);
    if (!matcher.matches()) {
      return;
    }
    String suite = matcher.group(1);
    String shortSuite = suite.substring(suite.lastIndexOf('.') + 1);
    if (!shortSuite.equals(currentSuite)) {
      flush();
      currentSuite = shortSuite;
    }
    tests++;
    switch (matcher.group(3)) {
      case "FAILED" -> {
        failed++;
        log.line("      FAILED " + shortSuite + "::" + matcher.group(2));
      }
      case "SKIPPED" -> skipped++;
      default -> { }
    }
  }

  /** Logs the summary line for the suite currently being counted. */
  void flush() {
    if (currentSuite == null) {
      return;
    }
    log.line("      " + currentSuite + ": " + tests + " tests"
             + (failed > 0 ? ", " + failed + " FAILED" : "")
             + (skipped > 0 ? ", " + skipped + " skipped" : ""));
    currentSuite = null;
    tests = 0;
    failed = 0;
    skipped = 0;
  }
}
