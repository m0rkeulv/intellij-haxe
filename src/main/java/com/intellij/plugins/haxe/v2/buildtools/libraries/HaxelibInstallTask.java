package com.intellij.plugins.haxe.v2.buildtools.libraries;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCommandNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One background {@code haxelib install} with its user-facing outcome: a
 * notification either way and, after a success, a library sync with
 * {@code onSynced} once it completed. Shared by the tool window's Install
 * action and the missing-library quick fix.
 */
public final class HaxelibInstallTask {

  private HaxelibInstallTask() {
  }

  /** {@code version} is the pinned version (null for the latest), {@code resolvedVersion} the one haxelib currently selects - see {@link HaxelibInstaller#install}. */
  public static void queue(@NotNull Project project,
                           @NotNull String name,
                           @Nullable String version,
                           @Nullable String resolvedVersion,
                           @Nullable Runnable onSynced) {
    new Task.Backgroundable(project, HaxeBundle.message("haxe.toolwindow.install.library.progress", name), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        String failure = HaxelibInstaller.install(project, name, version, resolvedVersion);
        if (failure == null) {
          String installed = HaxeBundle.message("haxe.toolwindow.install.library.success", name);
          HaxeCommandNotifications.notify(project, installed, NotificationType.INFORMATION);
          HaxeLibrarySync.sync(project, onSynced);
        }
        else {
          String title = HaxeBundle.message("haxe.toolwindow.install.library.failed", name);
          HaxeCommandNotifications.notify(project, title, failure, NotificationType.ERROR);
        }
      }
    }.queue();
  }
}
