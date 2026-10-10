package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Keeps custom actions and the references to them with their build file when it, or a folder above it, moves or is renamed. */
public final class HaxeCustomActionsMoveTracker implements BulkFileListener {
  private final Project project;

  public HaxeCustomActionsMoveTracker(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
    if (project.isDisposed()) return;
    HaxeCustomActionsStore store = HaxeCustomActionsStore.getInstance(project);
    for (VFileEvent event : events) {
      switch (event) {
        case VFileMoveEvent move -> store.moveOwner(move.getOldPath(), move.getNewPath());
        case VFilePropertyChangeEvent change when change.isRename() -> store.moveOwner(change.getOldPath(), change.getNewPath());
        default -> { }
      }
    }
  }
}
