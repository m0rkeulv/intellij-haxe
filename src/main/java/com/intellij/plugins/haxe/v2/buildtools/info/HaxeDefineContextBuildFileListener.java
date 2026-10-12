package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
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
 * re-evaluates the contexts in use and re-lexes what changed. Any project
 * file with a build-file extension counts (a refresh that finds nothing
 * changed costs one parse per context in use); the IDE's own settings files
 * are xml too and are left out, as are files outside the project's content.
 */
public final class HaxeDefineContextBuildFileListener implements BulkFileListener {

  /** Where the IDE keeps its settings (workspace.xml, the plugin's stores), saved on the IDE's own schedule. */
  private static final String IDE_SETTINGS_FOLDER = "/.idea/";

  private final Project project;

  public HaxeDefineContextBuildFileListener(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    if (project.isDisposed()) return;
    if (ContainerUtil.exists(events, this::concernsBuildFile)) {
      HaxeDefineContextService.getInstance(project).refreshAsync();
    }
  }

  /** Whether the event may change a build file's defines: a project file with a build-file extension saved, or one deleted. */
  boolean concernsBuildFile(@NotNull VFileEvent event) {
    String path = event.getPath();
    if (!HaxeBuildFileScanner.hasBuildFileExtension(path) || path.contains(IDE_SETTINGS_FOLDER)) return false;
    return switch (event) {
      case VFileContentChangeEvent change -> ProjectFileIndex.getInstance(project).isInContent(change.getFile());
      // a deleted file is in no content root any more; its path decided above
      case VFileDeleteEvent ignored -> true;
      default -> false;
    };
  }
}
