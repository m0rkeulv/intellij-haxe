package com.intellij.plugins.haxe.haxelib;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// A haxelib git version spec as build files pin it
/// (`-lib name:git:https://host/repo.git#ref` in hxml, the same
/// `git:URL[#ref]` form in haxelib.json dependencies); the optional ref is a
/// branch, tag or commit hash. Haxelib records such an install simply as
/// version `git`, so the spec never appears among installed versions.
public record HaxelibGitSpec(@NotNull String url, @Nullable String ref) {

  private static final String GIT_PREFIX = "git:";

  /** Parses a pinned version string, or null when it is not a usable git spec. */
  @Nullable
  public static HaxelibGitSpec parse(@Nullable String version) {
    if (version == null || !version.startsWith(GIT_PREFIX)) return null;
    String spec = version.substring(GIT_PREFIX.length());
    // the '#' separates the optional ref - clone urls carry no fragment
    int hash = spec.indexOf('#');
    String url = hash < 0 ? spec : spec.substring(0, hash);
    if (url.isBlank()) return null;
    String ref = hash < 0 ? null : spec.substring(hash + 1);
    return new HaxelibGitSpec(url, ref == null || ref.isBlank() ? null : ref);
  }

  /**
   * A ref that is a spelled-out commit hash shortened to git's abbreviated
   * form; branch and tag names pass through untouched.
   */
  @NotNull
  public static String shortRef(@NotNull String ref) {
    // 12+ hex-only chars read as a commit hash (a full SHA-1 has 40); real
    // branch/tag names of that shape are practically nonexistent
    boolean commitHash = ref.length() >= 12 && ref.matches("[0-9a-fA-F]+");
    return commitHash ? ref.substring(0, 10) : ref;
  }
}
