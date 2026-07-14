package com.intellij.plugins.haxe.hxcpp.adapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps live variablesReferences to the expression path of their container.
 *
 * DAP's {@code setVariable} names a child of a variablesReference, but the
 * server's {@code setVariable} takes an expression string — so every time a
 * reference passes through the adapter we record how to spell its contents:
 * a scope's children are bare names, an object's children are
 * {@code container.name}, an array's are {@code container[index]}.
 *
 * References are per-stop (the server clears its own on every stop), so the
 * adapter clears this registry whenever the debuggee resumes or stops — a
 * stale reference must fail, not alias another stop's values.
 */
class VariablePathRegistry {
  private final Map<Integer, String> containerExprByRef = new ConcurrentHashMap<>();

  /** A scope reference: children are addressed by their bare name. */
  void registerScope(int reference) {
    containerExprByRef.put(reference, "");
  }

  /** A reference whose children are addressed relative to {@code expression}. */
  void registerExpression(int reference, String expression) {
    if (reference > 0) {
      containerExprByRef.put(reference, expression);
    }
  }

  /** Registers an expandable child of a known container under its own path. */
  void registerChild(int parentReference, String childName, int childReference) {
    if (childReference > 0) {
      registerExpression(childReference, childExpression(parentReference, childName));
    }
  }

  /**
   * Spells the expression for {@code name} inside the container behind
   * {@code reference}; null when the reference is unknown (stale).
   */
  String childExpression(int reference, String name) {
    String container = containerExprByRef.get(reference);
    if (container == null) {
      return null;
    }
    if (container.isEmpty()) {
      return name;
    }
    if (isIndex(name)) {
      return container + "[" + name + "]";
    }
    return container + "." + name;
  }

  void clear() {
    containerExprByRef.clear();
  }

  private static boolean isIndex(String name) {
    if (name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      if (!Character.isDigit(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
