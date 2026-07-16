package com.intellij.plugins.haxe.runner.debugger;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.model.HaxeBaseMemberModel;
import com.intellij.plugins.haxe.util.HaxeElementGenerator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XNavigatable;
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
 * position (so locals and imports resolve exactly as they would in the
 * evaluate dialog), and the OUTERMOST reference — the full chain, whose
 * reference name is the selected member — is resolved. The fragment is
 * discarded afterwards; resolve-cache entries keyed on its elements die with
 * it. No path or no resolution simply means no navigation.
 *
 * The {@code this} keyword is special-cased: it does not reliably resolve
 * through a detached fragment (both adapters name the receiver local "this",
 * and such paths never navigated on either debugger). Bare {@code this} goes
 * to the enclosing class; {@code this.member…} drops the prefix and resolves
 * the remainder unqualified (instance members are in method scope via
 * implicit this), with a class-model lookup of the first member as a
 * fallback. Every bail-out is debug-logged so a silent non-navigation is
 * diagnosable from idea.log.
 */
public final class HaxeVariableSourceNavigator {
  private static final Logger LOG = Logger.getInstance(HaxeVariableSourceNavigator.class);

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
    if (target == null) {
      LOG.debug("jump-to-source: no target for path '" + path + "'");
    }
    navigatable.setSourcePosition(target);
  }

  private static @Nullable XSourcePosition resolvePosition(Project project, @Nullable XSourcePosition frame, String path) {
    PsiElement context = frame != null
                         ? HaxeDebuggerSupportUtils.getContextElement(frame.getFile(), frame.getOffset(), project)
                         : null;
    if (context == null) {
      // without a frame context nothing (locals, imports) can resolve
      LOG.debug("jump-to-source: no context element (frame position " + frame + ")");
      return null;
    }

    String expression = path;
    if (expression.equals("this")) {
      HaxeClass enclosing = PsiTreeUtil.getParentOfType(context, HaxeClass.class);
      LOG.debug("jump-to-source: bare this -> enclosing class " + (enclosing != null ? enclosing.getName() : null));
      return enclosing != null ? XDebuggerUtil.getInstance().createPositionByElement(enclosing.getNavigationElement()) : null;
    }
    if (expression.startsWith("this.")) {
      expression = expression.substring("this.".length());
    }

    XSourcePosition byResolve = resolveChain(project, context, expression);
    if (byResolve != null) {
      return byResolve;
    }
    // Fallback for a member the fragment could not resolve (e.g. `this.x` in a
    // context where implicit-this lookup fails): find the FIRST segment on the
    // enclosing class model — exact for `this.x`; for deeper unresolvable
    // chains, landing on the first member still beats not navigating.
    if (!expression.equals(path)) {
      return resolveOnEnclosingClass(context, expression);
    }
    return null;
  }

  private static @Nullable XSourcePosition resolveChain(Project project, PsiElement context, String expression) {
    PsiFile fragment = HaxeElementGenerator.createExpressionCodeFragment(project, expression, context, false);
    HaxeReference chain = outermostReference(fragment);
    if (chain == null) {
      LOG.debug("jump-to-source: no reference parsed from '" + expression + "'");
      return null;
    }
    PsiElement resolved = chain.resolve();
    if (resolved == null) {
      LOG.debug("jump-to-source: '" + chain.getText() + "' did not resolve");
      return null;
    }
    return XDebuggerUtil.getInstance().createPositionByElement(resolved.getNavigationElement());
  }

  private static @Nullable XSourcePosition resolveOnEnclosingClass(PsiElement context, String expression) {
    HaxeClass enclosing = PsiTreeUtil.getParentOfType(context, HaxeClass.class);
    if (enclosing == null) {
      LOG.debug("jump-to-source: no enclosing class for this-fallback");
      return null;
    }
    int dot = expression.indexOf('.');
    int bracket = expression.indexOf('[');
    int end = expression.length();
    if (dot >= 0) end = Math.min(end, dot);
    if (bracket >= 0) end = Math.min(end, bracket);
    String member = expression.substring(0, end);
    HaxeBaseMemberModel model = enclosing.getModel().getMember(member, null);
    if (model == null) {
      LOG.debug("jump-to-source: member '" + member + "' not found on " + enclosing.getName());
      return null;
    }
    return XDebuggerUtil.getInstance().createPositionByElement(model.getBasePsi().getNavigationElement());
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
