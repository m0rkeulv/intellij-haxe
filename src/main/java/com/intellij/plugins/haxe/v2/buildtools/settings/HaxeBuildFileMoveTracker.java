package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Keeps every store entry keyed by a file path with its file when it, or a folder above it, moves or is renamed. */
public final class HaxeBuildFileMoveTracker implements BulkFileListener {

  private final Project project;

  public HaxeBuildFileMoveTracker(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    if (project.isDisposed()) return;
    boolean changed = false;
    for (VFileEvent event : events) {
      changed |= switch (event) {
        case VFileMoveEvent move -> moveStored(move.getOldPath(), move.getNewPath());
        case VFilePropertyChangeEvent change when change.isRename() -> moveStored(change.getOldPath(), change.getNewPath());
        default -> false;
      };
    }
    if (changed) {
      HaxeBuildSettingsListener.publisher(project).buildFilesChanged();
    }
  }

  private boolean moveStored(@NotNull String oldPath, @NotNull String newPath) {
    boolean changed = HaxeCustomActionsStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeTargetSelectionStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeSectionSelectionStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeWorkDirectoryStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeBuildFilesStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeActiveBuildFileStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeEnvironmentStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeIdeOverridesStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeTestsBuildFileStore.getInstance(project).moveFile(oldPath, newPath);
    changed |= HaxeResolveContextStore.getInstance(project).moveFile(oldPath, newPath);
    return changed;
  }
}
