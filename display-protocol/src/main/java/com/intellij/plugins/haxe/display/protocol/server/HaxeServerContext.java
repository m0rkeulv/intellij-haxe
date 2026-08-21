package com.intellij.plugins.haxe.display.protocol.server;

import java.util.List;
import java.util.Map;

/**
 * One cache context of the server ({@code server/contexts}). The signature
 * identifies the argument set; the {@code after_init_macros} context is the
 * one holding typed modules.
 */
public record HaxeServerContext(int index,
                                String desc,
                                String signature,
                                String platform,
                                List<String> classPaths,
                                Map<String, String> defines) {
}
