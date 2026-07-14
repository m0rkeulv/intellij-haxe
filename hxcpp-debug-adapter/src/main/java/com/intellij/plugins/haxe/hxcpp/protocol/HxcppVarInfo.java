package com.intellij.plugins.haxe.hxcpp.protocol;

/**
 * One variable of a {@code getVariables}/{@code evaluate}/{@code setVariable}
 * result (VarInfo in Protocol.hx). {@code variablesReference} > 0 marks an
 * expandable value.
 */
public record HxcppVarInfo(String name, String type, String value, int variablesReference,
                           Integer namedVariables, Integer indexedVariables) {
}
