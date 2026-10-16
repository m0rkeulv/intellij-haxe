package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.Location;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.v2.display.HaxeCompilerDisplayService.DisplayContext;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Go-to-declaration answered by the compilation server. For a reference
 * under the caret, the targets are the PSI elements at the locations
 * {@code display/definition} returns, so the platform navigates, previews
 * and offers a chooser for several targets just as for static results. The
 * platform calls handlers under a cancelable progress, and the request waits
 * a bounded time. Ctrl-hover asks too, so every hovered reference costs one
 * request.
 * <p>
 * A name that neither the server nor the static resolver can place is taken
 * for a declaration a macro generated into the enclosing member, such as the
 * parameter a build macro wraps around an expression; its target is the
 * generated-code preview of that member, where the declaration exists. No
 * targets are returned for a declaration under the caret, where the
 * platform keeps its show-usages behaviour, nor for a file the compiler
 * cannot serve, where the notification is shown and the platform resolves
 * the reference itself.
 */
public final class HaxeCompilerGotoDeclarationHandler implements GotoDeclarationHandler {

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
    if (sourceElement == null) return null;
    HaxeReference reference = PsiTreeUtil.getParentOfType(sourceElement, HaxeReference.class, false);
    if (reference == null) return null;
    VirtualFile file = HaxeCompilerDisplayService.physicalFileOf(sourceElement);
    if (file == null) return null;
    Project project = sourceElement.getProject();
    HaxeCompilerNavigationService service = HaxeCompilerNavigationService.getInstance(project);
    if (!service.ensureAvailable(file)) return null;

    List<PsiElement> declarations = declarationsOf(project, service.definitionUnderReadLock(file, offset));
    if (!declarations.isEmpty()) return declarations.toArray(PsiElement.EMPTY_ARRAY);
    DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(file);
    PsiElement generated = context == null ? null : generatedDeclarationOf(reference, context);
    return generated == null ? null : new PsiElement[]{generated};
  }

  @NotNull
  private static List<PsiElement> declarationsOf(@NotNull Project project, @NotNull List<Location> locations) {
    List<PsiElement> declarations = new ArrayList<>();
    for (Location location : locations) {
      PsiFile psiFile = HaxeCompilerLocations.psiFileOf(project, location);
      PsiElement declaration = psiFile == null ? null : HaxeCompilerLocations.declarationIn(psiFile, location.range());
      if (declaration != null) declarations.add(declaration);
    }
    return declarations;
  }

  /**
   * The generated-code preview of the member the reference sits in, as the
   * target for a name the server could not place. Null when the static
   * resolver places the name (the platform then navigates there itself) or
   * the reference sits outside a class member. Read action.
   */
  @Nullable
  static PsiElement generatedDeclarationOf(@NotNull HaxeReference reference, @NotNull DisplayContext context) {
    String name = reference.getReferenceName();
    HaxeNamedComponent member = HaxeGeneratedPreviewSpot.enclosingMember(reference);
    if (name == null || member == null || reference.resolve() != null) return null;
    HaxeGeneratedPreviewSpot spot = HaxeGeneratedPreviewSpot.around(reference, name);
    return spot == null ? null : HaxeGeneratedPreviewTarget.createElement(member, context, spot);
  }
}
