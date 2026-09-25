package com.intellij.plugins.haxe.display.transport;

import java.util.List;

/**
 * One server response, classified by line: the payload lines joined (the
 * JSON-RPC envelope for a display request, compiler output otherwise), the
 * log lines (0x01-prefixed on the wire) and whether the fatal-error marker
 * (0x02) appeared.
 */
public record DisplayResponse(String payload, List<String> logs, boolean hasError) {
}
