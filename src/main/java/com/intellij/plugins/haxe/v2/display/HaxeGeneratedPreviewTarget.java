package com.intellij.plugins.haxe.v2.display;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeProjectTrust;
import com.intellij.plugins.haxe.v2.display.HaxeGeneratedCodePreview.PreparedPreview;
import com.intellij.plugins.haxe.v2.display.HaxeGeneratedDumpService.Dumped;
import com.intellij.plugins.haxe.v2.display.HaxeGeneratedDumpService.Failed;
import com.intellij.pom.PomNamedTarget;
import com.intellij.pom.references.PomService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiTarget;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.Future;

/**
 * A navigation target whose navigate() builds the module dump and opens the
 * generated-code preview, wrapped by {@link #createElement} as the PSI
 * element navigation surfaces expect. The navigation element is the
 * previewed declaration: it passes the navigability checks and supplies the
 * icon and location shown in popups, and the implementation popup takes the
 * target back out of the wrapper as its own navigatable. Go-to-declaration
 * would navigate to that element itself without calling navigate(), so the
 * Haxe target-element evaluator keeps the wrapper as the target there. A
 * dump that cannot be built ends in a notification saying why.
 */
@CustomLog
public final class HaxeGeneratedPreviewTarget implements PsiTarget, PomNamedTarget {

  private static final String NOTIFICATION_GROUP = "haxe.compiler";

  /** The preview to open, or the reason there is none, as shown to the user. */
  private record Preparation(@Nullable PreparedPreview preview, @Nullable String failure) {
  }

  private final PsiElement previewedElement;
  private final HaxeCompilerDisplayService.DisplayContext context;
  private final HaxeGeneratedPreviewSpot spot;

  private HaxeGeneratedPreviewTarget(@NotNull PsiElement previewedElement,
                                     @NotNull HaxeCompilerDisplayService.DisplayContext context,
                                     @NotNull HaxeGeneratedPreviewSpot spot) {
    this.previewedElement = previewedElement;
    this.context = context;
    this.spot = spot;
  }

  /** A preview opening on the spot, wrapped as the PSI element navigation surfaces expect. */
  @NotNull
  public static PsiElement createElement(@NotNull PsiElement previewedElement,
                                         @NotNull HaxeCompilerDisplayService.DisplayContext context,
                                         @NotNull HaxeGeneratedPreviewSpot spot) {
    HaxeGeneratedPreviewTarget target = new HaxeGeneratedPreviewTarget(previewedElement, context, spot);
    return PomService.convertToPsi(previewedElement.getProject(), target);
  }

  @NotNull
  HaxeGeneratedPreviewSpot spot() {
    return spot;
  }

  @Override
  public @NotNull String getName() {
    return HaxeBundle.message("haxe.generated.preview.target.name", spot.title());
  }

  @Override
  public @NotNull PsiElement getNavigationElement() {
    return previewedElement;
  }

  @Override
  public boolean isValid() {
    return previewedElement.isValid();
  }

  @Override
  public boolean canNavigate() {
    return true;
  }

  @Override
  public boolean canNavigateToSource() {
    return true;
  }

  @Override
  public void navigate(boolean requestFocus) {
    Project project = previewedElement.getProject();
    // the dump is a full compile - macros run
    if (!HaxeProjectTrust.confirmForAction(project, HaxeBundle.message("haxe.trust.action.generated.preview"))) {
      return;
    }
    // modal so an uncached dump (a full compile) is visibly in progress and
    // cancelable, instead of an easily missed status-bar task
    String title = HaxeBundle.message("haxe.generated.preview.progress.title");
    Task.Modal dumpTask = new Task.Modal(project, title, true) {
      private Preparation prepared;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        // the dump compile blocks in socket IO and cannot observe the
        // indicator, so it runs on its own thread. Cancel closes the dialog
        // immediately, while an abandoned build still finishes and lands in
        // the cache for the next attempt.
        Future<Preparation> preparing = ApplicationManager.getApplication().executeOnPooledThread(() -> preparePreview(project));
        prepared = HaxeCancelableFutures.awaitUnderProgress(preparing, indicator, "generated-code preview preparation failed");
      }

      @Override
      public void onSuccess() {
        if (prepared == null) return;
        if (prepared.preview() != null) {
          HaxeGeneratedCodePreview.openPrepared(project, prepared.preview());
        }
        else if (prepared.failure() != null) {
          notifyFailure(project, prepared.failure());
        }
      }
    };
    dumpTask.queue();
  }

  @NotNull
  private Preparation preparePreview(@NotNull Project project) {
    HaxeGeneratedDumpService dumpService = HaxeGeneratedDumpService.getInstance(project);
    return switch (dumpService.ensureDumps(context)) {
      case Failed failed -> failed(failed.message());
      case Dumped dumped -> prepareFrom(project, dumpService, dumped.targetDumpDir());
    };
  }

  @NotNull
  private Preparation prepareFrom(@NotNull Project project, @NotNull HaxeGeneratedDumpService dumpService, @NotNull Path targetDir) {
    Path moduleDump = dumpService.findModuleDump(targetDir, spot.moduleDotPath());
    if (moduleDump == null) return failed(HaxeBundle.message("haxe.generated.preview.failed.no.module", spot.moduleDotPath()));
    // rendering and the offset lookup parse the dump, which can be
    // library-sized, so they stay off the EDT
    PreparedPreview preview = HaxeGeneratedCodePreview.prepare(project, moduleDump, spot);
    if (preview == null) return failed(HaxeBundle.message("haxe.generated.preview.failed.unreadable", spot.moduleDotPath()));
    return new Preparation(preview, null);
  }

  @NotNull
  private Preparation failed(@NotNull String message) {
    log.info("generated-code preview of " + spot.moduleDotPath() + ": " + message);
    return new Preparation(null, message);
  }

  private static void notifyFailure(@NotNull Project project, @NotNull String message) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(HaxeBundle.message("haxe.generated.preview.failed.title"), message, NotificationType.WARNING)
      .notify(project);
  }
}
