package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hydrated {@link TypeBlueprint}s keyed by (context signature, module path,
 * type name). One {@code server/type} request per generated type, then every
 * member lookup in the editor answers from here. The context signature
 * changes whenever the argument set does, so stale contexts age out
 * naturally; per-file invalidation is the caller's job (it knows which
 * modules a change dirties, via {@code server/module} dependents).
 */
public class BlueprintCache {

  private record Key(String signature, String modulePath, String typeName) {
  }

  private final Map<Key, TypeBlueprint> blueprints = new ConcurrentHashMap<>();

  public TypeBlueprint get(String signature, String modulePath, String typeName) {
    return blueprints.get(new Key(signature, modulePath, typeName));
  }

  public void put(String signature, String modulePath, String typeName, TypeBlueprint blueprint) {
    blueprints.put(new Key(signature, modulePath, typeName), blueprint);
  }

  /** Drops every blueprint of one module (a file changed). */
  public void invalidateModule(String signature, String modulePath) {
    blueprints.keySet().removeIf(key -> key.signature().equals(signature) && key.modulePath().equals(modulePath));
  }

  /** Drops every blueprint of one context (the argument set changed or a build ran). */
  public void invalidateSignature(String signature) {
    blueprints.keySet().removeIf(key -> key.signature().equals(signature));
  }

  public void clear() {
    blueprints.clear();
  }

  public int size() {
    return blueprints.size();
  }
}
