package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.io.StreamUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the shipped live-reporter Haxe sources (see the READMEs under
 * {@code resources/testing/}) into the IDE system directory, so test compiles
 * can add them via {@code -cp} and inject the framework's reporter with its
 * {@code --macro} entry point. The target directory is keyed by a content
 * hash: a plugin update with changed sources lands in a fresh directory, and
 * an unchanged one reuses the previous extraction.
 */
@CustomLog
final class HaxeTestReporterFiles {

  private HaxeTestReporterFiles() {
  }

  /** The utest reporter's classpath root, or empty when extraction fails (the run then uses utest's batch reporter only). */
  @NotNull
  static Optional<String> utestClasspath() {
    return extract("/testing/utestLiveReporter/", "utest-live-reporter",
                   List.of("intellij_utest/Macro.hx", "intellij_utest/LiveReporter.hx", "intellij_utest/FlashSupport.hx"));
  }

  /** The munit reporter's classpath root, or empty when extraction fails (the run then reports to the console only). */
  @NotNull
  static Optional<String> munitClasspath() {
    return extract("/testing/munitLiveReporter/", "munit-live-reporter",
                   List.of("intellij_munit/Macro.hx", "intellij_munit/LiveClient.hx", "intellij_munit/FlashSupport.hx"));
  }

  /** The buddy reporter's classpath root, or empty when extraction fails (the run then reports to the console only). */
  @NotNull
  static Optional<String> buddyClasspath() {
    return extract("/testing/buddyLiveReporter/", "buddy-live-reporter",
                   List.of("intellij_buddy/TcReporter.hx", "intellij_buddy/SuiteName.hx"));
  }

  /** The tink reporter's classpath root, or empty when extraction fails (the run then reports to the console only). */
  @NotNull
  static Optional<String> tinkClasspath() {
    return extract("/testing/tinkLiveReporter/", "tink-live-reporter",
                   List.of("intellij_tink/Macro.hx", "intellij_tink/TcReporter.hx", "intellij_tink/FlashSupport.hx"));
  }

  @NotNull
  private static Optional<String> extract(@NotNull String resourceRoot,
                                          @NotNull String directoryName,
                                          @NotNull List<String> sourceFiles) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String file : sourceFiles) {
        digest.update(readResource(resourceRoot, file));
      }
      String contentHash = HexFormat.of().formatHex(digest.digest()).substring(0, 16);

      Path root = Path.of(PathManager.getSystemPath(), "haxe", directoryName, contentHash);
      for (String file : sourceFiles) {
        Path target = root.resolve(file);
        if (!Files.exists(target)) {
          Files.createDirectories(target.getParent());
          Files.write(target, readResource(resourceRoot, file));
        }
      }
      return Optional.of(root.toString());
    }
    catch (IOException | RuntimeException e) {
      log.warn("test reporter extraction failed for " + resourceRoot, e);
      return Optional.empty();
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @NotNull
  private static byte[] readResource(@NotNull String resourceRoot, @NotNull String relativePath) throws IOException {
    try (InputStream stream = HaxeTestReporterFiles.class.getResourceAsStream(resourceRoot + relativePath)) {
      if (stream == null) {
        throw new IOException("missing resource " + resourceRoot + relativePath);
      }
      return StreamUtil.readBytes(stream);
    }
  }
}
