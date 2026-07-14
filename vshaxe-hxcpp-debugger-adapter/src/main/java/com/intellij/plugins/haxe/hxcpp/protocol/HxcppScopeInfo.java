package com.intellij.plugins.haxe.hxcpp.protocol;

/**
 * One scope of a {@code getScopes} result (ScopeInfo in Protocol.hx).
 * {@code id} doubles as the variablesReference for {@code getVariables}.
 */
public record HxcppScopeInfo(int id, String name, Integer namedVariables, Integer indexedVariables) {
}
