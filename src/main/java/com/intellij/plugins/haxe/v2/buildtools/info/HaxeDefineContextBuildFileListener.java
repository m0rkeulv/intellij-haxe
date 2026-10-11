package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A saved or deleted build file, or a configuration file one includes,
 * changes the defines of the files lexed under it: the define context
 * re-evaluates the contexts in use and re-lexes what changed. Keyed on the
 * extension alone; a refresh that finds nothing changed costs one parse per
 * context in use.
 */
public final class HaxeDefineContextBuildFileListener implements BulkFileListener {

  private final Project project;

  public HaxeDefineContextBuildFileListener(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    if (project.isDisposed()) return;
    if (ContainerUtil.exists(events, HaxeDefineContextBuildFileListener::changesBuildFile)) {
      HaxeDefineContextService.getInstance(project).refreshAsync();
    }
  }

  private static boolean changesBuildFile(@NotNull VFileEvent event) {
    boolean contentOrDeletion = event instanceof VFileContentChangeEvent || event instanceof VFileDeleteEvent;
    return contentOrDeletion && HaxeBuildFileScanner.hasBuildFileExtension(event.getPath());
  }
}
