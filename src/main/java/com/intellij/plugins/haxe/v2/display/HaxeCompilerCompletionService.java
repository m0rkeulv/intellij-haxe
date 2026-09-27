package com.intellij.plugins.haxe.v2.display;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.CompletionItem;
import com.intellij.plugins.haxe.display.protocol.CompletionList;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompletionMode;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The compilation server's completion for a caret, one {@code display/completion}
 * request per popup: the file's build context, an invalidate for an unsaved
 * buffer (a cached module ignores {@code contents} otherwise), the request
 * itself. Availability is decided up front and cheaply: a file without a
 * build context, or a project whose compilation server is switched off,
 * gets no request at all and, once per project, the notification naming
 * the two ways out (enable the server, or switch the source to the IDE).
 * Completion runs under a read action where the services do no network,
 * so the request runs on a pooled thread and the caller waits with the
 * cancellable await, bounded by {@link #ANSWER_TIMEOUT_MS}: a keystroke
 * cancels the wait, a slow first answer (a server still starting, a lime
 * display run) is given up on while the request finishes on its own and
 * warms the next popup.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerCompletionService {

  private static final String NOTIFICATION_GROUP = "haxe.compiler";
  private static final long ANSWER_TIMEOUT_MS = 3_000;

  private final Project project;
  private volatile boolean unavailabilityNotified;

  public static HaxeCompilerCompletionService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerCompletionService.class);
  }

  public HaxeCompilerCompletionService(@NotNull Project project) {
    this.project = project;
  }

  /**
   * Whether the file can be completed by the compiler at all: the project's
   * compilation server is switched on, the file's container has a build
   * command (the display context falls back to a lone known build file,
   * which answers nothing useful without the command's setup), and a build
   * context derives from it. False raises the notification, once per
   * project. Read action; no network.
   */
  public boolean ensureAvailable(@NotNull VirtualFile file) {
    boolean serverEnabled = HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled();
    boolean available = serverEnabled && hasBuildCommand(file) && HaxeCompilerDisplayService.getInstance(project).contextFor(file) != null;
    if (!available) notifyUnavailableOnce();
    return available;
  }

  private boolean hasBuildCommand(@NotNull VirtualFile file) {
    String containerId = HaxeContainers.containerIdFor(project, file);
    return HaxeEnvironmentStore.getInstance(project).getCompileCommand(containerId) != null;
  }

  /**
   * The compiler's items at the offset, or null when the server does not
   * answer within the timeout or at all, or completion was cancelled (the
   * platform's cancellation propagates). Read action, after
   * {@link #ensureAvailable}.
   */
  @Nullable
  public CompletionList complete(@NotNull VirtualFile file, @NotNull String contents, boolean unsaved, int offset, boolean autoTriggered) {
    HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
    HaxeCompilerDisplayService.DisplayContext context = displayService.contextFor(file);
    if (context == null) return null;
    String path = file.getPath();
    Future<CompletionList> request = ApplicationManager.getApplication()
      .executeOnPooledThread(() -> fetch(displayService, context, path, contents, unsaved, offset, autoTriggered));
    return awaitAnswer(request, path);
  }

  /** Waits for the answer under cancellation checks, giving up after the timeout with nothing. */
  @Nullable
  private static CompletionList awaitAnswer(Future<CompletionList> request, String path) {
    long deadline = System.currentTimeMillis() + ANSWER_TIMEOUT_MS;
    ProgressIndicatorUtils.awaitWithCheckCanceled(() -> request.isDone() || System.currentTimeMillis() >= deadline);
    if (!request.isDone()) {
      log.info("display/completion for " + path + " gave no answer within " + ANSWER_TIMEOUT_MS + " ms");
      return null;
    }
    try {
      return request.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      log.warn("display/completion failed for " + path + ": " + e.getCause());
      return null;
    }
  }

  /**
   * The doc comment of the item at the index of the popup's completion, from
   * a {@code display/completionItem/resolve} request against the same
   * server, for an item the completion listed without its doc. Background
   * thread, no read lock held by the caller; null when there is nothing to
   * resolve against or the declaration has no doc.
   */
  @Nullable
  public String resolveDoc(@NotNull VirtualFile file, int index) {
    HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
    HaxeCompilerDisplayService.DisplayContext context = HaxeReadActions.compute(() -> displayService.contextFor(file));
    if (context == null) return null;
    HaxeCompilerDisplayService.Connected connected = displayService.connectFor(context, DisplayMethods.COMPLETION_ITEM_RESOLVE);
    if (connected == null) return null;
    try {
      CompletionItem resolved = connected.client().resolveCompletionItem(connected.args(), index);
      return resolved == null ? null : resolved.doc();
    } catch (DisplayRequestException e) {
      log.info("display/completionItem/resolve failed for " + file.getPath() + " #" + index + ": " + e.getMessage());
      return null;
    }
  }

  @Nullable
  private static CompletionList fetch(HaxeCompilerDisplayService displayService, HaxeCompilerDisplayService.DisplayContext context,
                                      String path, String contents, boolean unsaved, int offset, boolean autoTriggered) {
    HaxeCompilerDisplayService.Connected connected = displayService.connectFor(context, DisplayMethods.COMPLETION);
    if (connected == null) return null;
    try {
      if (unsaved) {
        connected.client().invalidate(connected.args(), path);
      }
      return connected.client().completion(connected.args(), path, offset, unsaved ? contents : null, autoTriggered);
    } catch (DisplayRequestException e) {
      log.info("display/completion failed for " + path + ": " + e.getMessage());
      return null;
    }
  }

  private void notifyUnavailableOnce() {
    if (unavailabilityNotified) return;
    unavailabilityNotified = true;
    String title = HaxeBundle.message("haxe.compiler.completion.unavailable.title");
    String content = HaxeBundle.message("haxe.compiler.completion.unavailable.content");
    Notification notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(title, content, NotificationType.WARNING);
    notification.addAction(NotificationAction.createSimpleExpiring(
      HaxeBundle.message("haxe.compiler.completion.unavailable.configure"),
      () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, HaxeBuildToolsConfigurable.class)));
    notification.addAction(NotificationAction.createSimpleExpiring(
      HaxeBundle.message("haxe.compiler.completion.unavailable.use.ide"),
      () -> HaxeCompilerSettings.getInstance(project).setCompletionMode(HaxeCompletionMode.IDE_ONLY)));
    notification.notify(project);
  }

  @TestOnly
  public boolean unavailabilityNotifiedForTests() {
    return unavailabilityNotified;
  }
}
