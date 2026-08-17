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
    if (HaxelibSemVer.DEV.equals(version)) {
      return devDirectory(libraryRoot);
    }
    Path directory = HaxelibSemVer.GIT_SCM.equals(version)
                     ? libraryRoot.resolve(HaxelibSemVer.GIT_SCM)
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

  // ------------------------------------------------- dev/git pseudo-versions

  /** The checkout state of a git pseudo-version; either part may be missing. */
  public record GitCheckout(@Nullable String branch, @Nullable String commit) {
  }

  /** Where the library's dev pseudo-version points (the {@code .dev} file's directory), or null. */
  @Nullable
  public static String devPath(@NotNull Path repoRoot, @NotNull String name) {
    Path directory = devDirectory(repoRoot.resolve(name));
    return directory == null ? null : directory.toString();
  }

  /**
   * The git pseudo-version's checkout state, read from the clone's own
   * metadata ({@code .git/HEAD} plus loose/packed refs) — never by running
   * git, so it works without a git installation and executes nothing.
   */
  @Nullable
  public static GitCheckout gitCheckout(@NotNull Path repoRoot, @NotNull String name) {
    Path gitDir = gitMetadataDirectory(repoRoot.resolve(name).resolve(HaxelibSemVer.GIT_SCM));
    if (gitDir == null) return null;
    String head = readTrimmed(gitDir.resolve("HEAD"));
    if (head == null) return null;

    if (!head.startsWith("ref: ")) {
      // detached checkout: HEAD is the commit itself
      return new GitCheckout(null, head);
    }
    String ref = head.substring("ref: ".length()).trim();
    // the branch is the ref's last segment (refs/heads/main -> main)
    String branch = ref.substring(ref.lastIndexOf('/') + 1);
    String commit = readTrimmed(gitDir.resolve(ref));
    if (commit == null) {
      commit = packedRefCommit(gitDir, ref);
    }
    return new GitCheckout(branch, commit);
  }

  /** {@code .git} is a directory in a plain clone, but a pointer FILE ({@code gitdir: <path>}) in worktrees. */
  @Nullable
  private static Path gitMetadataDirectory(@NotNull Path checkoutDir) {
    Path dotGit = checkoutDir.resolve(".git");
    if (Files.isDirectory(dotGit)) return dotGit;
    String pointer = readTrimmed(dotGit);
    if (pointer == null || !pointer.startsWith("gitdir:")) return null;
    Path resolved = checkoutDir.resolve(pointer.substring("gitdir:".length()).trim()).normalize();
    return Files.isDirectory(resolved) ? resolved : null;
  }

  @Nullable
  private static String packedRefCommit(@NotNull Path gitDir, @NotNull String ref) {
    try {
      // packed-refs lines are "<hash> <ref>" (peeled "^<hash>" lines excluded by the space match)
      return Files.readAllLines(gitDir.resolve("packed-refs")).stream()
        .filter(line -> line.endsWith(" " + ref))
        .map(line -> line.substring(0, line.indexOf(' ')))
        .findFirst()
        .orElse(null);
    }
    catch (IOException | RuntimeException e) {
      return null;
    }
  }

  @Nullable
  private static String readTrimmed(@NotNull Path file) {
    try {
      String content = Files.readString(file).trim();
      return content.isEmpty() ? null : content;
    }
    catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
