package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.psi.HaxeReferenceExpression;
import com.intellij.plugins.haxe.v2.buildtools.HaxeProjectTrust;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Redirects go-to-declaration on members that exist ONLY in the compiler's
 * post-macro world (blueprint-resolved, no source declaration) to the
 * generated-code preview: a dump build produces the module's typed-AST text
 * and the caret lands on the member. The dump build is wrapped in a
 * cancelable background task — canceling abandons the navigation, not the
 * build; a finished build still lands in the cache for the next attempt.
 */
@CustomLog
public class HaxeGeneratedPreviewGotoHandler implements GotoDeclarationHandler {

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
    if (sourceElement == null) return null;
    Project project = sourceElement.getProject();
    if (!HaxeCompilerSettings.getInstance(project).getCompletionMode().usesCompiler()) return null;

    HaxeReferenceExpression reference = PsiTreeUtil.getParentOfType(sourceElement, HaxeReferenceExpression.class, false);
    if (reference == null) return null;

    PsiElement resolved = reference.resolve();
    if (resolved == null) return null;
    PsiFile resolvedFile = resolved.getContainingFile();
    if (resolvedFile == null) return null;
    String dotPath = resolvedFile.getUserData(HaxeCompilerResolveService.BLUEPRINT_DOT_PATH);
    if (dotPath == null) return null;

    PsiFile sourceFile = sourceElement.getContainingFile();
    VirtualFile contextFile = sourceFile != null ? sourceFile.getOriginalFile().getVirtualFile() : null;
    if (contextFile == null) return null;
    HaxeCompilerDisplayService.DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(contextFile);
    if (context == null) return null;

    String memberName = reference.getReferenceName();
    return new PsiElement[]{new PreviewTarget(resolved, context, dotPath, memberName)};
  }

  /**
   * A navigation target whose navigate() runs the dump flow instead of
   * opening its (non-physical) declaration. Presentation delegates to the
   * blueprint member so the goto popup shows the real name and type.
   */
  private static final class PreviewTarget extends FakePsiElement {
    private final PsiElement blueprintMember;
    private final HaxeCompilerDisplayService.DisplayContext context;
    private final String dotPath;
    private final String memberName;

    private PreviewTarget(@NotNull PsiElement blueprintMember,
                          @NotNull HaxeCompilerDisplayService.DisplayContext context,
                          @NotNull String dotPath,
                          @Nullable String memberName) {
      this.blueprintMember = blueprintMember;
      this.context = context;
      this.dotPath = dotPath;
      this.memberName = memberName;
    }

    @Override
    public PsiElement getParent() {
      return blueprintMember.getParent();
    }

    // getNavigationElement() intentionally NOT overridden: goto navigates to
    // getNavigationElement(), and redirecting it to the (non-physical)
    // blueprint member turns navigation into a silent no-op. The default
    // returns this element, so the platform calls navigate() below.

    @Override
    public String getName() {
      return memberName != null ? memberName : dotPath;
    }

    @Override
    public boolean canNavigate() {
      return true;
    }

    @Override
    public void navigate(boolean requestFocus) {
      Project project = blueprintMember.getProject();
      // the dump is a full compile - macros run
      if (!HaxeProjectTrust.confirmForAction(project, HaxeBundle.message("haxe.trust.action.generated.preview"))) {
        return;
      }
      String title = HaxeBundle.message("haxe.generated.preview.progress.title");
      Task.Backgroundable dumpTask = new Task.Backgroundable(project, title, true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          HaxeGeneratedDumpService dumpService = HaxeGeneratedDumpService.getInstance(project);
          Path targetDir = dumpService.ensureDumps(context);
          if (targetDir == null) {
            log.info("generated-code preview: no dump produced for " + dotPath);
            return;
          }
          Path moduleDump = dumpService.findModuleDump(targetDir, dotPath);
          if (moduleDump == null) {
            log.info("generated-code preview: no module dump for " + dotPath);
            return;
          }
          // still on the background thread: rendering + offset lookup parse
          // the (library-sized) dump and must stay off the EDT
          HaxeGeneratedCodePreview.PreparedPreview prepared =
            HaxeGeneratedCodePreview.prepare(project, moduleDump, dotPath, memberName);
          if (prepared == null) return;
          // canceled while dumping: the finished dump stays cached, only the
          // navigation is abandoned
          if (indicator.isCanceled()) return;
          ApplicationManager.getApplication().invokeLater(
            () -> HaxeGeneratedCodePreview.openPrepared(project, prepared));
        }
      };
      dumpTask.queue();
    }
  }
}
