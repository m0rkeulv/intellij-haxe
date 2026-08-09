package com.intellij.plugins.haxe.v2.toolwindow.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.plugins.haxe.v2.buildtools.HaxeLibrarySync;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import com.intellij.plugins.haxe.v2.toolwindow.HaxeToolWindowPanel;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeToolWindowNodes.LibraryNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree context menu on a missing library: runs {@code haxelib install <name> [version] --always}
 * in the background and refreshes the tree on success.
 */
public final class HaxeInstallLibraryAction extends DumbAwareAction {

  private static final String NOTIFICATION_GROUP = "haxe.command";
  private static final int INSTALL_TIMEOUT_MS = 600_000;

  private final HaxeToolWindowPanel panel;

  public HaxeInstallLibraryAction(@NotNull HaxeToolWindowPanel panel) {
    this.panel = panel;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null && panel.getSelectedUserObject() instanceof LibraryNode library && !library.installed()) {
      installInBackground(project, library);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    if (panel.getSelectedUserObject() instanceof LibraryNode library && !library.installed()) {
      e.getPresentation().setEnabledAndVisible(true);
      e.getPresentation().setText(HaxeBundle.message("haxe.toolwindow.install.library", library.name()));
    }
    else {
      e.getPresentation().setEnabledAndVisible(false);
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  private void installInBackground(@NotNull Project project, @NotNull LibraryNode library) {
    new Task.Backgroundable(project, HaxeBundle.message("haxe.toolwindow.install.library.progress", library.name()), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        String failure = installQuietly(project, library);
        if (failure == null) {
          notifyUser(project, HaxeBundle.message("haxe.toolwindow.install.library.success", library.name()),
                     "", NotificationType.INFORMATION);
          HaxeLibrarySync.sync(project, panel::refreshTree);
        }
        else {
          notifyUser(project, HaxeBundle.message("haxe.toolwindow.install.library.failed", library.name()),
                     failure, NotificationType.ERROR);
        }
      }
    }.queue();
  }

  /**
   * Runs one {@code haxelib install} (restoring the previously selected version
   * when the install would hijack it); null on success, else the failure detail.
   * Call on a background thread.
   */
  @Nullable
  static String installQuietly(@NotNull Project project, @NotNull LibraryNode library) {
    GeneralCommandLine commandLine = new GeneralCommandLine()
      .withExePath(HaxeToolPathResolver.resolveHaxelibExecutable(project))
      .withParameters(installParameters(library))
      .withWorkDirectory(project.getBasePath());
    try {
      ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(INSTALL_TIMEOUT_MS);
      if (output.getExitCode() != 0 || output.isTimeout()) {
        return StringUtil.trimTrailing(output.getStdout() + "\n" + output.getStderr());
      }
      restoreSelectedVersion(project, library);
      return null;
    }
    catch (ExecutionException e) {
      return StringUtil.notNullize(e.getMessage());
    }
  }

  // "haxelib install name [version] --always"; git/path pseudo-versions cannot be passed to install
  @NotNull
  private static List<String> installParameters(@NotNull LibraryNode library) {
    var parameters = new ArrayList<>(List.of("install", library.name()));
    String version = library.version();
    if (isPlainVersion(version)) {
      parameters.add(version);
    }
    parameters.add("--always");
    return parameters;
  }

  private static boolean isPlainVersion(@Nullable String version) {
    // a release version like 1.2.3 or 9.2.0-rc.1 - not git:/path: source specs
    return version != null && version.matches("\\d+(\\.\\d+)*([-.].*)?");
  }

  /**
   * Installing a pinned version makes it haxelib's SELECTED version as a side
   * effect, silently switching every unpinned project. Restore the previous
   * selection ("dev"/"git" pseudo-versions cannot be re-set and stay put).
   */
  private static void restoreSelectedVersion(@NotNull Project project, @NotNull LibraryNode library) {
    String previous = library.resolvedVersion();
    boolean selectionHijacked = isPlainVersion(library.version())
                                && isPlainVersion(previous)
                                && !previous.equals(library.version());
    if (!selectionHijacked) return;

    GeneralCommandLine commandLine = new GeneralCommandLine()
      .withExePath(HaxeToolPathResolver.resolveHaxelibExecutable(project))
      .withParameters("set", library.name(), previous, "--always")
      .withWorkDirectory(project.getBasePath());
    try {
      new CapturingProcessHandler(commandLine).runProcess(INSTALL_TIMEOUT_MS);
    }
    catch (ExecutionException e) {
      // the install itself succeeded; a failed restore only leaves the new version selected
    }
  }

  static void notifyUser(@NotNull Project project, @NotNull String title, @NotNull String content,
                         @NotNull NotificationType type) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(title, content, type)
      .notify(project);
  }
}
