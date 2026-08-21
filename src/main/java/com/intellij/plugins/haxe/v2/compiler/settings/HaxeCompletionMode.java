package com.intellij.plugins.haxe.v2.compiler.settings;

import com.intellij.plugins.haxe.HaxeBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where completion and resolve get their symbols: the IDE's static analysis
 * alone, or enriched with compiler-known symbols (macro-generated types and
 * members the source never declares). Gates the compiler-backed resolve and
 * completion paths; diagnostics highlighting has its own toggle.
 */
// TODO: a COMPILER_ONLY mode (every completion answered by the compilation
//  server) needs per-request display/completion wiring with unsaved-buffer
//  sync and is out of scope for now.
public enum HaxeCompletionMode {
  IDE_ONLY("ide", "haxe.compiler.completion.mode.ide"),
  IDE_AND_COMPILER("ide+compiler", "haxe.compiler.completion.mode.ide.and.compiler");

  private final String id;
  private final String presentableKey;

  HaxeCompletionMode(String id, String presentableKey) {
    this.id = id;
    this.presentableKey = presentableKey;
  }

  /** Stable identifier used for persistence. */
  @NotNull
  public String getId() {
    return id;
  }

  @NotNull
  public String getPresentableText() {
    return HaxeBundle.message(presentableKey);
  }

  public boolean usesCompiler() {
    return this != IDE_ONLY;
  }

  @NotNull
  public static HaxeCompletionMode fromId(@Nullable String id) {
    for (HaxeCompletionMode mode : values()) {
      if (mode.id.equals(id)) return mode;
    }
    return IDE_AND_COMPILER;
  }
}
