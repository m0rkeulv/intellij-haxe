package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Announces a saved or deleted build file, or a configuration file one
 * includes, as a {@link HaxeBuildEventListener} event. Any project file with
 * a build-file extension counts; the IDE's own settings files are xml too
 * and are left out, as are files outside the project's content.
 */
public final class HaxeBuildFileChangeListener implements BulkFileListener {
  /** Where the IDE keeps its settings (workspace.xml, the plugin's stores), saved on the IDE's own schedule. */
  private static final String IDE_SETTINGS_FOLDER = "/.idea/";

  private final Project project;

  public HaxeBuildFileChangeListener(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    if (project.isDisposed()) return;
    HaxeBuildEventListener publisher = HaxeBuildEventListener.publisher(project);
    for (VFileEvent event : events) {
      if (!concernsBuildFile(event)) continue;
      if (event instanceof VFileDeleteEvent) {
        publisher.buildFileDeleted(event.getPath());
      }
      else {
        publisher.buildFileContentChanged(event.getPath());
      }
    }
  }

  /** Whether the event may change a build file's meaning: a project file with a build-file extension saved, or one deleted. */
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
