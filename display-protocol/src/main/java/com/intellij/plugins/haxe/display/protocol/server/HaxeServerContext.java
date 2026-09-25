package com.intellij.plugins.haxe.display.protocol.server;

import java.util.Map;

/**
 * One cache context of the server ({@code server/contexts}). The
 * {@code signature} identifies the argument set the context was compiled with.
 */
public record HaxeServerContext(String desc,
                                String signature,
                                String platform,
                                Map<String, String> defines) {

  /** The {@code desc} of the context holding the typed program's modules. */
  public static final String TYPED_MODULES_DESC = "after_init_macros";

  /**
   * Whether this context holds the typed program's modules. The server also
   * keeps a macro context, whose modules exist only for the macro interpreter.
   */
  public boolean holdsTypedModules() {
    return TYPED_MODULES_DESC.equals(desc);
  }
}
