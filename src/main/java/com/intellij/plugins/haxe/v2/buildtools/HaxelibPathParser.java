package com.intellij.plugins.haxe.v2.buildtools;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses `haxelib path <lib>` output: bare lines are classpath roots (including the
 * lib's dependencies), lines starting with a dash are compiler flags (-D/-L), and
 * error text has no path shape.
 */
public final class HaxelibPathParser {

  private HaxelibPathParser() {
  }

  /** One library of a {@code haxelib path} run: its own classpath roots only, not its dependencies'. */
  public record LibrarySection(@NotNull String name, @Nullable String version, @NotNull List<String> classpaths) {
  }

  /**
   * Splits {@code haxelib path <lib>} output into per-library sections: each
   * classpath line belongs to the {@code -D name=version} marker that FOLLOWS
   * it (the requested lib prints first, dependencies after, each closing its
   * own section). Attributing the whole output to the requested lib mounts
   * dependency sources under the wrong External Libraries entry — and, with
   * version skew between entries, duplicates type definitions.
   * Trailing classpaths without a marker (extraParams.hxml additions) fall to
   * {@code requestedLib} with no version.
   */
  @NotNull
  public static List<LibrarySection> parseSections(@NotNull String requestedLib, @NotNull List<String> outputLines) {
    List<LibrarySection> sections = new ArrayList<>();
    List<String> pending = new ArrayList<>();
    for (String line : outputLines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.startsWith("-D ")) {
        int equals = trimmed.indexOf('=');
        if (equals > 3) {
          String name = trimmed.substring(3, equals).trim();
          String version = trimmed.substring(equals + 1).trim();
          sections.add(new LibrarySection(name, version, List.copyOf(pending)));
          pending.clear();
        }
        continue;
      }
      if (trimmed.startsWith("-")) continue;
      if (trimmed.startsWith("Error") || trimmed.contains("is not installed")) continue;
      pending.add(trimmed);
    }
    if (!pending.isEmpty()) {
      sections.add(new LibrarySection(requestedLib, null, List.copyOf(pending)));
    }
    return sections;
  }

  @NotNull
  public static List<String> parseClasspaths(@NotNull List<String> outputLines) {
    List<String> classpaths = new ArrayList<>();
    for (String line : outputLines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("-")) continue;
      if (trimmed.startsWith("Error") || trimmed.contains("is not installed")) continue;
      classpaths.add(trimmed);
    }
    return classpaths;
  }

  /**
   * The resolved version of {@code libName}: haxelib closes each library's
   * section with a {@code -D name=version} line (dependencies close with their
   * own, so the name must match). Null when the marker is absent.
   */
  @Nullable
  public static String parseVersion(@NotNull String libName, @NotNull List<String> outputLines) {
    for (String line : outputLines) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("-D ")) continue;
      int equals = trimmed.indexOf('=');
      if (equals < 0) continue;
      String name = trimmed.substring(3, equals).trim();
      if (name.equalsIgnoreCase(libName)) {
        return trimmed.substring(equals + 1).trim();
      }
    }
    return null;
  }
}
