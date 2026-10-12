package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EventListener;

/**
 * Something was observed or computed about the build: a build file changed
 * on disk, a library sync or a build-tool evaluation finished, a failure was
 * recorded, the module workspace was written. Never a user editing a
 * setting, which is
 * {@link com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildSettingsListener}.
 * Delivered on the publisher's thread, often a background one: a consumer
 * touching UI or PSI dispatches first.
 */
public interface HaxeBuildEventListener extends EventListener {
  @Topic.ProjectLevel
  Topic<HaxeBuildEventListener> TOPIC = new Topic<>(HaxeBuildEventListener.class, Topic.BroadcastDirection.NONE);

  /** A build file, or a file one includes, was saved to disk. Not a trigger for processes: the "Load changes" banner is. */
  default void buildFileContentChanged(@NotNull String path) {
    anyBuildEvent();
  }

  default void buildFileDeleted(@NotNull String path) {
    anyBuildEvent();
  }

  /** A library sync run finished, whether or not it changed anything. */
  default void librariesSynced() {
    anyBuildEvent();
  }

  /** A lime, openfl or nme evaluation of the build file landed in its cache (fresh, or a retry of a failed run). */
  default void evaluationLanded(@NotNull String buildFilePath) {
    anyBuildEvent();
  }

  /** Module libraries or SDK entries were written to the project model. */
  default void workspaceApplied() {
    anyBuildEvent();
  }

  /** A build-tool or compiler-context failure appeared, changed or cleared for the key. */
  default void failureRecorded(@NotNull String key) {
    anyBuildEvent();
  }

  /** Any of the above, for a consumer reacting to every event; the specific methods reach it unless overridden. */
  default void anyBuildEvent() {
  }

  /** A listener reacting the same way to every event. */
  @NotNull
  static HaxeBuildEventListener onAny(@NotNull Runnable reaction) {
    return new HaxeBuildEventListener() {
      @Override
      public void anyBuildEvent() {
        reaction.run();
      }
    };
  }

  @NotNull
  static HaxeBuildEventListener publisher(@NotNull Project project) {
    return project.getMessageBus().syncPublisher(TOPIC);
  }

  /** A publisher that publishes into the void, for a disposed project. */
  @Nullable
  static HaxeBuildEventListener publisherIfOpen(@NotNull Project project) {
    return project.isDisposed() ? null : publisher(project);
  }
}
