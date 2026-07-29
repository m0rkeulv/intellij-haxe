package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.util.messages.Topic;

/**
 * Project-bus notification that the Haxe build configuration was (re)synced -
 * build files reparsed, libraries resynced. UI showing derived state (the tool
 * window tree) refreshes on it.
 */
@FunctionalInterface
public interface HaxeBuildConfigListener {

  @Topic.ProjectLevel
  Topic<HaxeBuildConfigListener> TOPIC =
    new Topic<>("Haxe build configuration", HaxeBuildConfigListener.class, Topic.BroadcastDirection.NONE);

  void buildConfigurationChanged();
}
