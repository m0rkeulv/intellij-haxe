package com.intellij.plugins.haxe.display.protocol.server;

import java.util.List;

/**
 * {@code server/memory}: the server's total cache size plus each compilation
 * context's share, all in bytes.
 */
public record ServerMemory(long totalCache, List<ContextSize> contexts) {

  /** One context's cache memory; matches {@code server/contexts} entries by signature. */
  public record ContextSize(HaxeServerContext context, long size) {
  }
}
