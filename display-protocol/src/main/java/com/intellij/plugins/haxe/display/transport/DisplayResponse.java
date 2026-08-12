package com.intellij.plugins.haxe.display.transport;

import java.util.List;

/**
 * The classified content of one server response: payload lines (the JSON-RPC
 * envelope for display requests, compiler output otherwise), log lines
 * (0x01-prefixed) and whether the fatal-error marker (0x02) appeared.
 */
public record DisplayResponse(String payload, List<String> logs, boolean hasError) {
}
