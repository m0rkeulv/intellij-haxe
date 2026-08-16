package com.intellij.plugins.haxe.haxelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Locates an installed library version's local files in the haxelib
 * repository: the version directory (haxelib stores dots as commas, a git
 * checkout under {@code git}, and a dev version wherever the {@code .dev}
 * pointer file says) and the well-known documentation files inside it.
 */
public final class HaxelibLocalDocs {

  /** Doc base names offered as tabs, in display order. */
  private static final List<String> DOC_BASE_NAMES = List.of("readme", "changelog", "license");

  private HaxelibLocalDocs() {
  }

  /** The directory holding the installed version's files, or null when it cannot be resolved. */
  @Nullable
  public static Path versionDirectory(@NotNull Path repoRoot, @NotNull String name, @NotNull String version) {
    Path libraryRoot = repoRoot.resolve(name);
    if ("dev".equals(version)) {
      return devDirectory(libraryRoot);
    }
    Path directory = "git".equals(version)
                     ? libraryRoot.resolve("git")
                     : libraryRoot.resolve(version.replace('.', ','));
    return Files.isDirectory(directory) ? directory : null;
  }

  @Nullable
  private static Path devDirectory(@NotNull Path libraryRoot) {
    Path pointer = libraryRoot.resolve(".dev");
    try {
      String target = Files.readString(pointer).trim();
      Path directory = Path.of(target);
      return Files.isDirectory(directory) ? directory : null;
    }
    catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The version directory's documentation files (README/CHANGELOG/LICENSE,
   * any case, {@code .md}/{@code .txt}/bare), one per base name in display
   * order.
   */
  @NotNull
  public static List<Path> docFiles(@NotNull Path versionDirectory) {
    List<Path> found = new ArrayList<>();
    try (var entries = Files.list(versionDirectory)) {
      List<Path> files = entries.filter(Files::isRegularFile).toList();
      for (String base : DOC_BASE_NAMES) {
        files.stream()
          .filter(file -> matchesBaseName(file, base))
          .findFirst()
          .ifPresent(found::add);
      }
    }
    catch (IOException e) {
      return List.of();
    }
    return found;
  }

  private static boolean matchesBaseName(@NotNull Path file, @NotNull String base) {
    String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return fileName.equals(base) || fileName.equals(base + ".md") || fileName.equals(base + ".txt");
  }
}
