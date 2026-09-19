package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeProjectTrust;
import com.intellij.pom.PomNamedTarget;
import com.intellij.pom.references.PomService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiTarget;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A navigation target whose navigate() runs the generated-preview dump flow.
 * A POM target rather than a PSI element: the navigation pipelines turn a
 * plain PSI element into a file/offset descriptor built from its containing
 * file without ever calling navigate(), but route a PomTargetPsiElement to
 * its target's own navigation. The navigation element is the previewed
 * declaration - it satisfies the pipelines' navigability checks and supplies
 * the popup presentation's icon and location. Create the element navigation
 * surfaces expect with {@link #createElement}.
 */
@CustomLog
public final class HaxeGeneratedPreviewTarget implements PsiTarget, PomNamedTarget {

  private final PsiElement previewedElement;
  private final HaxeCompilerDisplayService.DisplayContext context;
  private final String dotPath;
  private final String memberName;

  private HaxeGeneratedPreviewTarget(@NotNull PsiElement previewedElement,
                                     @NotNull HaxeCompilerDisplayService.DisplayContext context,
                                     @NotNull String dotPath,
                                     @Nullable String memberName) {
    this.previewedElement = previewedElement;
    this.context = context;
    this.dotPath = dotPath;
    this.memberName = memberName;
  }

  /** A preview of the given type (or member) wrapped as the PSI element navigation surfaces expect. */
  @NotNull
  public static PsiElement createElement(@NotNull PsiElement previewedElement,
                                         @NotNull HaxeCompilerDisplayService.DisplayContext context,
                                         @NotNull String dotPath,
                                         @Nullable String memberName) {
    HaxeGeneratedPreviewTarget target = new HaxeGeneratedPreviewTarget(previewedElement, context, dotPath, memberName);
    return PomService.convertToPsi(previewedElement.getProject(), target);
  }

  @Override
  public @NotNull String getName() {
    return HaxeBundle.message("haxe.generated.preview.target.name", memberName != null ? memberName : dotPath);
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
      private HaxeGeneratedCodePreview.PreparedPreview prepared;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        // the dump compile blocks in socket IO and cannot observe the
        // indicator, so it runs on its own thread: Cancel closes the dialog
        // immediately while an abandoned build still finishes and lands in
        // the cache for the next attempt
        Future<HaxeGeneratedCodePreview.PreparedPreview> preparing =
          ApplicationManager.getApplication().executeOnPooledThread(() -> preparePreview(project));
        prepared = awaitCancelable(preparing, indicator);
      }

      @Override
      public void onSuccess() {
        if (prepared != null) HaxeGeneratedCodePreview.openPrepared(project, prepared);
      }
    };
    dumpTask.queue();
  }

  @Nullable
  private HaxeGeneratedCodePreview.PreparedPreview preparePreview(@NotNull Project project) {
    HaxeGeneratedDumpService dumpService = HaxeGeneratedDumpService.getInstance(project);
    Path targetDir = dumpService.ensureDumps(context);
    if (targetDir == null) {
      log.info("generated-code preview: no dump produced for " + dotPath);
      return null;
    }
    Path moduleDump = dumpService.findModuleDump(targetDir, dotPath);
    if (moduleDump == null) {
      log.info("generated-code preview: no module dump for " + dotPath);
      return null;
    }
    // rendering + offset lookup parse the (library-sized) dump and must stay
    // off the EDT
    return HaxeGeneratedCodePreview.prepare(project, moduleDump, dotPath, memberName);
  }

  /** Waits for the preparation, aborting the task (dialog included) the moment the user cancels. */
  @Nullable
  private static HaxeGeneratedCodePreview.PreparedPreview awaitCancelable(
    @NotNull Future<HaxeGeneratedCodePreview.PreparedPreview> preparing,
    @NotNull ProgressIndicator indicator) {
    while (true) {
      indicator.checkCanceled();
      try {
        return preparing.get(100, TimeUnit.MILLISECONDS);
      }
      catch (TimeoutException stillPreparing) {
        // keep polling; the wait must stay short so cancellation is prompt
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
      catch (ExecutionException e) {
        log.warn("generated-code preview preparation failed", e.getCause());
        return null;
      }
    }
  }
}
