package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hydrated {@link TypeBlueprint}s keyed by (context signature, module path,
 * type name). One {@code server/type} request per generated type, then every
 * member lookup in the editor answers from here. The context signature
 * changes whenever the argument set does, so stale contexts age out
 * naturally; anything finer than {@link #clear()} waits for the automatic
 * invalidation wiring (see the TODO in HaxeCompilerResolveService).
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

  public void clear() {
    blueprints.clear();
  }

  public int size() {
    return blueprints.size();
  }
}
