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
 * Extracts the shipped live-reporter Haxe sources (see
 * {@code resources/testing/utestLiveReporter/README.md}) into the IDE system
 * directory, so test compiles can add them via {@code -cp} and inject the
 * reporter with {@code --macro intellij_utest.Macro.init()}. The target
 * directory is keyed by a content hash: a plugin update with changed sources
 * lands in a fresh directory, and an unchanged one reuses the previous
 * extraction.
 */
@CustomLog
final class HaxeTestReporterFiles {

  private static final String RESOURCE_ROOT = "/testing/utestLiveReporter/";
  private static final List<String> SOURCE_FILES = List.of(
    "intellij_utest/Macro.hx",
    "intellij_utest/LiveReporter.hx");

  private HaxeTestReporterFiles() {
  }

  /** The extracted classpath root, or empty when extraction fails (the run then uses utest's batch reporter only). */
  @NotNull
  static Optional<String> classpath() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String file : SOURCE_FILES) {
        digest.update(readResource(file));
      }
      String contentHash = HexFormat.of().formatHex(digest.digest()).substring(0, 16);

      Path root = Path.of(PathManager.getSystemPath(), "haxe", "utest-live-reporter", contentHash);
      for (String file : SOURCE_FILES) {
        Path target = root.resolve(file);
        if (!Files.exists(target)) {
          Files.createDirectories(target.getParent());
          Files.write(target, readResource(file));
        }
      }
      return Optional.of(root.toString());
    }
    catch (IOException | RuntimeException e) {
      log.warn("live test reporter extraction failed; runs fall back to utest's batch reporter", e);
      return Optional.empty();
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @NotNull
  private static byte[] readResource(@NotNull String relativePath) throws IOException {
    try (InputStream stream = HaxeTestReporterFiles.class.getResourceAsStream(RESOURCE_ROOT + relativePath)) {
      if (stream == null) {
        throw new IOException("missing resource " + RESOURCE_ROOT + relativePath);
      }
      return StreamUtil.readBytes(stream);
    }
  }
}
