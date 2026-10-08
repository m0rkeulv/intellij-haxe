package com.intellij.plugins.haxe.v2.editor;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;

/** Re-queries the editor banners when the build configuration, and with it a build-tool failure, changes. */
public final class HaxeBuildToolFailureBannerRefresh implements HaxeBuildConfigListener {
  private final Project project;

  public HaxeBuildToolFailureBannerRefresh(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void buildConfigurationChanged() {
    EditorNotifications.getInstance(project).updateAllNotifications();
  }
}
