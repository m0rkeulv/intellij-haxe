package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildEventListener;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

/**
 * A modification tracker bumped by every build setting and build event, for
 * cached values that depend on the stores (active build file, targets,
 * sections, added files, overrides) without subscribing themselves.
 */
@Service(Service.Level.PROJECT)
public final class HaxeBuildSettingsTracker extends SimpleModificationTracker implements Disposable {

  public HaxeBuildSettingsTracker(@NotNull Project project) {
    MessageBusConnection connection = project.getMessageBus().connect(this);
    connection.subscribe(HaxeBuildSettingsListener.TOPIC, HaxeBuildSettingsListener.onAny(this::incModificationCount));
    connection.subscribe(HaxeBuildEventListener.TOPIC, HaxeBuildEventListener.onAny(this::incModificationCount));
  }

  @NotNull
  public static HaxeBuildSettingsTracker getInstance(@NotNull Project project) {
    return project.getService(HaxeBuildSettingsTracker.class);
  }

  @Override
  public void dispose() {
  }
}
