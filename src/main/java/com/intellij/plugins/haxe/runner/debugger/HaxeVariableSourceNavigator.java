package com.intellij.plugins.haxe.runner.debugger;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.util.HaxeElementGenerator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XNavigatable;
import com.intellij.xdebugger.impl.XSourcePositionImpl;
import org.jetbrains.annotations.Nullable;

/**
 * "Jump to Source" for a Variables-view node: resolves the node's access-path
 * expression ("this.someObject.someArray[0].myVar", the same path that
 * pre-fills Evaluate Expression) through the ordinary Haxe resolver and
 * navigates to the resolved declaration — the field in its declaring class,
 * or a local/parameter's declaration for a frame root. Mirrors the Java
 * debugger's behavior.
 *
 * Mechanism: the path is parsed into a throwaway {@code
 * HaxeExpressionCodeFragment} whose context is the current frame's source
 * position (so locals, {@code this} and imports resolve exactly as they would
 * in the evaluate dialog), and the OUTERMOST reference — the full chain, whose
 * reference name is the selected member — is resolved. The fragment is
 * discarded afterwards; resolve-cache entries keyed on its elements die with
 * it. No path or no resolution simply means no navigation.
 */
public final class HaxeVariableSourceNavigator {
  private HaxeVariableSourceNavigator() {
  }

  public static void navigate(@Nullable XDebugSession session, @Nullable String path, XNavigatable navigatable) {
    if (session == null || path == null || path.isBlank()) {
      navigatable.setSourcePosition(null);
      return;
    }
    Project project = session.getProject();
    XSourcePosition frame = session.getCurrentPosition();
    XSourcePosition target = ReadAction.compute(() -> resolvePosition(project, frame, path));
    navigatable.setSourcePosition(target);
  }

  private static @Nullable XSourcePosition resolvePosition(Project project, @Nullable XSourcePosition frame, String path) {
    PsiElement context = frame != null
                         ? HaxeDebuggerSupportUtils.getContextElement(frame.getFile(), frame.getOffset(), project)
                         : null;
    if (context == null) {
      return null; // without a frame context nothing (locals, imports) can resolve
    }
    PsiFile fragment = HaxeElementGenerator.createExpressionCodeFragment(project, path, context, false);
    HaxeReference chain = outermostReference(fragment);
    if (chain == null) {
      return null;
    }
    PsiElement resolved = chain.resolve();
    return resolved != null ? XSourcePositionImpl.createByElement(resolved.getNavigationElement()) : null;
  }

  // The outermost (widest) reference expression in the fragment — the full
  // access chain, whose reference name element is the LAST segment. findChildren
  // walks pre-order, so the first widest hit is the chain's top.
  private static @Nullable HaxeReference outermostReference(PsiFile fragment) {
    HaxeReference outermost = null;
    for (HaxeReference candidate : PsiTreeUtil.findChildrenOfType(fragment, HaxeReference.class)) {
      if (outermost == null || candidate.getTextLength() > outermost.getTextLength()) {
        outermost = candidate;
      }
    }
    return outermost;
  }
}
