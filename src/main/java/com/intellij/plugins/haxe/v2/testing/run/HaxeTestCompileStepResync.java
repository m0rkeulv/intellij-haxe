package com.intellij.plugins.haxe.v2.testing.run;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import org.jetbrains.annotations.NotNull;

/**
 * Recomputes test configurations' persisted before-run compile steps when the
 * build configuration is resynced (build files reparsed): the tests hxml's
 * content feeds those arguments (the suite-name define, single-stage vs
 * artifact), so an hxml edit must reach existing configurations without
 * waiting for them to be re-edited.
 */
public final class HaxeTestCompileStepResync implements HaxeBuildConfigListener {

  private final Project project;

  public HaxeTestCompileStepResync(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void buildConfigurationChanged() {
    // resyncCompileSteps schedules its own background compute + EDT apply
    if (!project.isDisposed()) {
      HaxeTestRunConfigurations.resyncCompileSteps(project);
    }
  }
}
