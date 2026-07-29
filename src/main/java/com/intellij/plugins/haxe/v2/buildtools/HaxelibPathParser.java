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
