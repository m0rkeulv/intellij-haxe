package com.intellij.plugins.haxe.v2.buildtools.server;

import com.intellij.util.messages.Topic;

/**
 * Project-bus notification for compilation server state transitions (started,
 * stopped, died, new port). Delivered on the EDT.
 */
@FunctionalInterface
public interface HaxeCompilationServerListener {

  @Topic.ProjectLevel
  Topic<HaxeCompilationServerListener> TOPIC =
    new Topic<>("Haxe compilation server state", HaxeCompilationServerListener.class, Topic.BroadcastDirection.NONE);

  void serverStateChanged();
}
