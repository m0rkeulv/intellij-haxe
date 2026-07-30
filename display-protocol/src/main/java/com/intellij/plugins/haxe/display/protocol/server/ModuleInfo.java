package com.intellij.plugins.haxe.display.protocol.server;

import java.util.List;

/**
 * The dependency-relevant subset of {@code server/module}: where the module
 * lives and which modules it depends on / is depended on by — the signal for
 * dropping cached blueprints when a file changes.
 */
public record ModuleInfo(String file,
                         String sign,
                         List<String> types,
                         List<String> dependencies,
                         List<String> dependents) {
}
