package com.intellij.plugins.haxe.v2.buildtools.libraries;

import com.intellij.plugins.haxe.util.HaxeExternalPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses `haxelib path <lib>` output: lines naming an absolute local path are
 * classpath roots (including the lib's dependencies), lines starting with a dash
 * are compiler flags (-D/-L). Anything else is haxelib talking - an error, or a
 * notice such as a newer haxelib's repository-format warning - and is skipped.
 */
public final class HaxelibPathParser {

  private static final String DEFINE_PREFIX = "-D ";

  private HaxelibPathParser() {
  }

  /// One library of a `haxelib path` run: its own classpath roots only, not its dependencies'.
  public record LibrarySection(@NotNull String name, @Nullable String version, @NotNull List<String> classpaths) {
  }

  /// Splits `haxelib path <lib>` output into per-library sections: each
  /// classpath line belongs to the `-D name=version` marker that FOLLOWS
  /// it (the requested lib prints first, dependencies after, each closing its
  /// own section). Attributing the whole output to the requested lib mounts
  /// dependency sources under the wrong External Libraries entry — and, with
  /// version skew between entries, duplicates type definitions.
  /// Trailing classpaths without a marker (extraParams.hxml additions) fall to
  /// `requestedLib` with no version.
  @NotNull
  public static List<LibrarySection> parseSections(@NotNull String requestedLib, @NotNull List<String> outputLines) {
    List<LibrarySection> sections = new ArrayList<>();
    List<String> pending = new ArrayList<>();
    for (String line : outputLines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.startsWith(DEFINE_PREFIX)) {
        int equals = trimmed.indexOf('=');
        if (equals > DEFINE_PREFIX.length()) {
          String name = trimmed.substring(DEFINE_PREFIX.length(), equals).trim();
          String version = trimmed.substring(equals + 1).trim();
          sections.add(new LibrarySection(name, version, List.copyOf(pending)));
          pending.clear();
        }
        continue;
      }
      if (trimmed.startsWith("-")) continue;
      if (HaxeExternalPaths.absolutePathOf(trimmed) == null) continue;
      pending.add(trimmed);
    }
    if (!pending.isEmpty()) {
      sections.add(new LibrarySection(requestedLib, null, List.copyOf(pending)));
    }
    return sections;
  }
}
