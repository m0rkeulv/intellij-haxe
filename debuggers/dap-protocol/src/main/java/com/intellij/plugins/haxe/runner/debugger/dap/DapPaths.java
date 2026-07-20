package com.intellij.plugins.haxe.runner.debugger.dap;

import java.util.Locale;

/**
 * Source-path normalization shared across the DAP debuggers. Windows paths
 * reach us with either separator and in varying case (the IDE, the compiler
 * and the runtime disagree), so matching and comparison go through here.
 */
public final class DapPaths {
  private DapPaths() {
  }

  /** {@code path} with backslashes normalized to forward slashes. */
  public static String toSlashes(String path) {
    return path.replace('\\', '/');
  }

  /**
   * A case-insensitive, slash-normalized key for matching source paths (e.g.
   * an IDE-sent breakpoint path against a VM-reported frame source). Case is
   * folded because haxe source trees do not distinguish files by case.
   */
  public static String normalizeKey(String path) {
    return toSlashes(path).toLowerCase(Locale.ROOT);
  }
}
