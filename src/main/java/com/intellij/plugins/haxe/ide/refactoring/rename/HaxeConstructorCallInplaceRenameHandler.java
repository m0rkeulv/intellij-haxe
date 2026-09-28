package com.intellij.plugins.haxe.ide.refactoring.rename;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.plugins.haxe.lang.psi.HaxeMethodPsiMixin;
import com.intellij.plugins.haxe.lang.psi.HaxeNewExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.inplace.InplaceRefactoring;
import com.intellij.refactoring.rename.inplace.MemberInplaceRenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * In-place rename on a constructor call renames the class. The target of
 * the reference in `new Helper()` is the constructor, whose Haxe name is
 * `new`, and the generic member handler seeds every occurrence with the
 * target's name. That handler stands down for constructors
 * ({@link com.intellij.plugins.haxe.ide.refactoring.HaxeRefactoringSupportProvider}),
 * leaving this one as the only available handler.
 */
public class HaxeConstructorCallInplaceRenameHandler extends MemberInplaceRenameHandler {

  @Override
  protected boolean isAvailable(@Nullable PsiElement element, @NotNull Editor editor, @NotNull PsiFile file) {
    PsiElement atCaret = file.findElementAt(editor.getCaretModel().getOffset());
    if (PsiTreeUtil.getParentOfType(atCaret, HaxeNewExpression.class) == null) return false;
    PsiClass renamed = classOfConstructor(element);
    return renamed != null && super.isAvailable(renamed, editor, file);
  }

  @Override
  public InplaceRefactoring doRename(@NotNull PsiElement elementToRename, @NotNull Editor editor, @Nullable DataContext dataContext) {
    PsiClass renamed = classOfConstructor(elementToRename);
    return super.doRename(renamed != null ? renamed : elementToRename, editor, dataContext);
  }

  @Nullable
  private static PsiClass classOfConstructor(@Nullable PsiElement element) {
    if (element instanceof HaxeMethodPsiMixin method && method.isConstructor()) return method.getContainingClass();
    return null;
  }
}
