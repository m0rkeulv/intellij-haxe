package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.util.messages.Topic;

/**
 * Fired on the project bus whenever a build-configuration store mutates
 * (active build file, target selection, environment). Consumers deriving
 * state from these stores (define context, tool window) subscribe and
 * invalidate; new invalidation sources publish the same topic instead of
 * every consumer learning about every source.
 */
public interface HaxeBuildSettingsListener {
  Topic<HaxeBuildSettingsListener> TOPIC = Topic.create("haxe build settings changed", HaxeBuildSettingsListener.class);

  void buildSettingsChanged();
}
