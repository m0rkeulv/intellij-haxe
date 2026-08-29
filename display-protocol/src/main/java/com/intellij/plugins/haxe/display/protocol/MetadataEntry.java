package com.intellij.plugins.haxe.display.protocol;

/**
 * One entry of the {@code display/metadata} registry: compiler-built-in
 * metadata plus anything libraries registered via
 * {@code Compiler.registerCustomMetadata}. {@code name} carries the leading
 * colon ({@code :bind}); {@code internal} marks compiler-internal metas not
 * meant for user code.
 */
public record MetadataEntry(String name, String doc, boolean internal) {

  /** The name without its leading colon — the form user code and PSI carry. */
  public String bareName() {
    return name.startsWith(":") ? name.substring(1) : name;
  }
}
