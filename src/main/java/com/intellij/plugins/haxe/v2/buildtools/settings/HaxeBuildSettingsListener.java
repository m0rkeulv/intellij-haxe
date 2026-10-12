package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EventListener;

/**
 * A persisted build choice changed. Published by the stores only, one
 * method per kind of choice; a consumer overrides the methods for the
 * choices it derives from, or {@link #anySettingChanged} for all of them.
 * Background results and observed facts are not settings: they go through
 * {@link com.intellij.plugins.haxe.v2.buildtools.HaxeBuildEventListener}.
 */
public interface HaxeBuildSettingsListener extends EventListener {
  @Topic.ProjectLevel
  Topic<HaxeBuildSettingsListener> TOPIC = new Topic<>(HaxeBuildSettingsListener.class, Topic.BroadcastDirection.NONE);

  /** The part of a container's environment that changed. */
  enum EnvironmentAspect { SDK, COMPILE_COMMAND, COMPILATION_SERVER, CUSTOM_TARGET, DEFINES, ALL }

  /** A store loaded its persisted state (project open); everything derived from it starts over. */
  default void stateLoaded() {
    anySettingChanged();
  }

  default void activeBuildFileChanged() {
    anySettingChanged();
  }

  /** A build file was registered, removed, hidden or moved. */
  default void buildFilesChanged() {
    anySettingChanged();
  }

  default void targetSelected(@NotNull String buildFilePath) {
    anySettingChanged();
  }

  default void sectionSelected(@NotNull String buildFilePath) {
    anySettingChanged();
  }

  default void environmentChanged(@NotNull String containerId, @NotNull EnvironmentAspect aspect) {
    anySettingChanged();
  }

  default void testsMarkChanged(@NotNull String containerId) {
    anySettingChanged();
  }

  default void workDirectoryChanged(@NotNull String buildFilePath) {
    anySettingChanged();
  }

  /** The custom actions of a build file changed; only the views showing them derive from it. */
  default void customActionsChanged(@NotNull String ownerId) {
    anySettingChanged();
  }

  default void customToolsChanged(@NotNull String containerId) {
    anySettingChanged();
  }

  /** The Haxe Compiler settings or a language level changed ({@code haxe_ver} and the Language level rows follow). */
  default void compilerSettingsChanged() {
    anySettingChanged();
  }

  /** Any of the above, for a consumer that derives from every choice; the specific methods reach it unless overridden. */
  default void anySettingChanged() {
  }

  /** A listener reacting the same way to every choice. */
  @NotNull
  static HaxeBuildSettingsListener onAny(@NotNull Runnable reaction) {
    return new HaxeBuildSettingsListener() {
      @Override
      public void anySettingChanged() {
        reaction.run();
      }
    };
  }

  /** The project's publisher; a store built without a project (state tests) publishes into the void. */
  @NotNull
  static HaxeBuildSettingsListener publisher(@Nullable Project project) {
    return project == null ? NONE : project.getMessageBus().syncPublisher(TOPIC);
  }

  HaxeBuildSettingsListener NONE = new HaxeBuildSettingsListener() {
  };
}
