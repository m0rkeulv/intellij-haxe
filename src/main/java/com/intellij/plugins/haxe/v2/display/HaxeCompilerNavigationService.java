package com.intellij.plugins.haxe.v2.display;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.FindReferencesKind;
import com.intellij.plugins.haxe.display.protocol.Location;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerDisplayService.Connected;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerDisplayService.DisplayContext;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.List;
import java.util.concurrent.Future;

/**
 * Asks the compilation server for Find Usages ({@code display/references})
 * and go-to-declaration ({@code display/definition}) at a position in a
 * file. Both are single, user-initiated requests that block on the network.
 * Find Usages calls from a progress thread. Go-to-declaration calls under
 * the read lock, so it uses the variant that runs the request on a pooled
 * thread and waits cancelably for a bounded time. A file with unsaved
 * changes is invalidated on the server first, because the server ignores
 * {@code contents} for a module it has cached. An offset travels as the
 * server counts it in the text it reads ({@link HaxeServerOffsets}).
 *
 * Availability is checked up front, as for completion: a file the compiler
 * cannot serve gets no request, and a notification, shown once per project,
 * offers to configure the server or to switch back to the IDE's features.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerNavigationService {

  private static final String NOTIFICATION_GROUP = "haxe.compiler";
  private static final long ANSWER_TIMEOUT_MS = 3_000;

  @FunctionalInterface
  private interface Request {
    List<Location> send(@NotNull Connected connected, @NotNull Payload payload) throws DisplayRequestException;
  }

  /** What a request carries for its file: the unsaved text when there is one, and the offset as the server counts it. */
  private record Payload(@Nullable String contents, int serverOffset) {
  }

  private final Project project;
  private volatile boolean unavailabilityNotified;

  public static HaxeCompilerNavigationService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerNavigationService.class);
  }

  public HaxeCompilerNavigationService(@NotNull Project project) {
    this.project = project;
  }

  /**
   * Whether the compiler can answer for this file (see
   * {@link HaxeCompilerFeatureAvailability}). A false answer shows the
   * notification, once per project. Call in a read action; no network.
   */
  public boolean ensureAvailable(@NotNull VirtualFile file) {
    boolean available = HaxeCompilerFeatureAvailability.isAvailable(project, file);
    if (!available) notifyUnavailableOnce();
    return available;
  }

  /** Every reference of the symbol at the offset, declaration excluded; empty when the server gives none. Background thread. */
  @NotNull
  public List<Location> references(@NotNull VirtualFile file, int offset, @NotNull FindReferencesKind kind) {
    String path = file.getPath();
    return request(file, offset, DisplayMethods.FIND_REFERENCES,
                   (connected, payload) -> connected.client().references(connected.args(), path, payload.serverOffset(), payload.contents(), kind));
  }

  /** The declarations of the symbol at the offset; empty when the server gives none. Background thread. */
  @NotNull
  public List<Location> definition(@NotNull VirtualFile file, int offset) {
    String path = file.getPath();
    return request(file, offset, DisplayMethods.GOTO_DEFINITION,
                   (connected, payload) -> connected.client().definition(connected.args(), path, payload.serverOffset(), payload.contents()));
  }

  /**
   * {@link #definition} for a caller that holds the read lock and so must
   * not block on the network: the request runs on a pooled thread while the
   * caller waits cancelably for at most {@link #ANSWER_TIMEOUT_MS}. Empty
   * when the server gives none or the wait runs out.
   */
  @NotNull
  public List<Location> definitionUnderReadLock(@NotNull VirtualFile file, int offset) {
    Future<List<Location>> request = ApplicationManager.getApplication().executeOnPooledThread(() -> definition(file, offset));
    String label = "display/definition for " + file.getPath();
    List<Location> answer = HaxeCancelableFutures.awaitUnderReadLock(request, ANSWER_TIMEOUT_MS, label);
    return answer == null ? List.of() : answer;
  }

  @NotNull
  private List<Location> request(@NotNull VirtualFile file, int offset, @NotNull String method, @NotNull Request request) {
    HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
    DisplayContext context = HaxeReadActions.compute(() -> displayService.contextFor(file));
    if (context == null) return List.of();
    Payload payload = HaxeReadActions.compute(() -> payloadOf(file, offset));
    Connected connected = displayService.connectFor(context, method);
    if (connected == null) return List.of();
    try {
      displayService.prepareFile(connected, file.getPath(), payload.contents());
      return request.send(connected, payload);
    } catch (DisplayRequestException e) {
      log.info(method + " failed for " + file.getPath() + ": " + e.getMessage());
      return List.of();
    }
  }

  /** Read action. */
  @NotNull
  private static Payload payloadOf(@NotNull VirtualFile file, int offset) {
    String contents = HaxeCompilerDisplayService.unsavedContents(file);
    return new Payload(contents, HaxeServerOffsets.serverOffset(file, offset, contents));
  }

  private void notifyUnavailableOnce() {
    if (unavailabilityNotified) return;
    unavailabilityNotified = true;
    String title = HaxeBundle.message("haxe.compiler.ide.features.unavailable.title");
    String content = HaxeBundle.message("haxe.compiler.ide.features.unavailable.content");
    Notification notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(title, content, NotificationType.WARNING);
    notification.addAction(NotificationAction.createSimpleExpiring(
      HaxeBundle.message("haxe.compiler.ide.features.unavailable.configure"),
      () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, HaxeBuildToolsConfigurable.class)));
    notification.addAction(NotificationAction.createSimpleExpiring(
      HaxeBundle.message("haxe.compiler.ide.features.unavailable.use.ide"),
      () -> HaxeCompilerSettings.getInstance(project).setCompilerIdeFeaturesEnabled(false)));
    notification.notify(project);
  }

  @TestOnly
  public boolean unavailabilityNotifiedForTests() {
    return unavailabilityNotified;
  }

  /** Arms the once-per-project notification again; the shared light project otherwise keeps it fired between tests. */
  @TestOnly
  public void resetUnavailabilityNotificationForTests() {
    unavailabilityNotified = false;
  }
}
