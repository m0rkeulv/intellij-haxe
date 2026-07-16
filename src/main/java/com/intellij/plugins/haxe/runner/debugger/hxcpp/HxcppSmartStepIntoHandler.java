package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.psi.HaxeCallExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler;
import com.intellij.xdebugger.stepping.XSmartStepIntoVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

/**
 * Smart step into for HXCPP: on a line with several calls (chained
 * {@code a().b()} or nested {@code a(b())}), lists them so the user picks
 * which one to enter. Both the dedicated action (Shift+F7) and the plain Step
 * Into (F7, via {@link #computeStepIntoVariants}) show the chooser; F7 steps
 * plainly when the line has at most one call.
 *
 * Unlike the HashLink handler, the variants are computed ENTIRELY from the
 * Haxe PSI — the in-debuggee server has no line→calls knowledge (there is no
 * bytecode to mine on hxcpp) — by resolving each call on the stopped line to
 * its declaring class. Choosing one sends the custom
 * {@code intellij/stepIntoFunction} request with (className, functionName);
 * the server races a temporary entry breakpoint against a step-over, so a
 * variant whose call never executes degrades safely to a step over.
 *
 * Filtered out: calls that do not resolve to a class method (closures, local
 * functions — no runtime class/function name exists for the entry breakpoint
 * to match), {@code inline} methods (no runtime function at all) and externs
 * (no instrumentation). Virtual dispatch caveat: the breakpoint is planted on
 * the DECLARED class, so entering an override called through a base-typed
 * reference lands as a step over instead.
 */
class HxcppSmartStepIntoHandler extends XSmartStepIntoHandler<HxcppSmartStepIntoHandler.Variant> {
  private final HxcppDebugProcess process;

  HxcppSmartStepIntoHandler(HxcppDebugProcess process) {
    this.process = process;
  }

  // The platform drives this async entry point; compute on the DAP request
  // thread — the sync computeSmartStepVariants must never block the EDT.
  @Override
  public @NotNull Promise<List<Variant>> computeSmartStepVariantsAsync(@NotNull XSourcePosition position) {
    AsyncPromise<List<Variant>> promise = new AsyncPromise<>();
    process.onRequestThread(() -> {
      try {
        promise.setResult(computeSmartStepVariants(position));
      } catch (Throwable t) {
        promise.setError(t);
      }
    });
    return promise;
  }

  @Override
  public @NotNull List<Variant> computeSmartStepVariants(@NotNull XSourcePosition position) {
    return ReadAction.compute(() -> resolveVariants(position));
  }

  // The PLAIN Step Into action (F7) consults this — the base implementation
  // returns a rejected promise, meaning "no variants, just step". Returning our
  // variants makes F7 behave like the Java debugger: with more than one call on
  // the line the same highlight/Tab chooser appears; with zero or one the
  // platform performs an ordinary step into.
  @Override
  public @NotNull Promise<List<Variant>> computeStepIntoVariants(@NotNull XSourcePosition position) {
    return computeSmartStepVariantsAsync(position);
  }

  // Every call on the stopped line whose callee resolves to a steppable class
  // method, in source order, with the call name's text range for highlighting.
  private List<Variant> resolveVariants(XSourcePosition position) {
    List<Variant> variants = new ArrayList<>();
    for (HaxeCallExpression call : callsOnLine(position)) {
      if (!(call.getExpression() instanceof HaxeReference reference)) {
        continue;
      }
      PsiElement name = reference.getReferenceNameElement();
      if (name == null || !(reference.resolve() instanceof HaxeMethod method)) {
        continue;
      }
      HaxeMethodModel model = method.getModel();
      if (model == null || model.isInline() || model.isExtern()) {
        continue; // no runtime function to enter
      }
      HaxeClassModel declaringClass = model.getDeclaringClass();
      String className = declaringClass != null ? runtimeClassName(declaringClass.haxeClass) : null;
      if (className == null || className.isEmpty()) {
        continue; // closures/local functions: no class-function name to break on
      }
      variants.add(new Variant(className, model.getName(), name.getTextRange()));
    }
    return variants;
  }

  // The class name as hxcpp's RUNTIME knows it: package + bare class name.
  // NOT PSI's getQualifiedName(): for an ancillary (secondary) class in a
  // module that includes the module segment ("pack.FileName.ClassName"),
  // while generated frames carry "pack.ClassName" — a mismatched name means
  // the entry breakpoint never fires and every choice degrades to step over.
  // (Verified live: a secondary class matched as its bare package+name.)
  private static @Nullable String runtimeClassName(HaxeClass haxeClass) {
    String name = haxeClass.getName();
    if (name == null || name.isEmpty()) {
      return null;
    }
    PsiFile file = haxeClass.getContainingFile();
    String packageName = file != null ? HaxeResolveUtil.getPackageName(file) : null;
    return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
  }

  // The call expressions whose NAME identifier sits on the position's line,
  // in source order.
  private List<HaxeCallExpression> callsOnLine(XSourcePosition position) {
    List<HaxeCallExpression> calls = new ArrayList<>();
    Project project = process.getSession().getProject();
    PsiFile file = PsiManager.getInstance(project).findFile(position.getFile());
    if (file == null) {
      return calls;
    }
    Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    if (document == null || position.getLine() < 0 || position.getLine() >= document.getLineCount()) {
      return calls;
    }
    int lineStart = document.getLineStartOffset(position.getLine());
    int lineEnd = document.getLineEndOffset(position.getLine());
    for (HaxeCallExpression call : PsiTreeUtil.findChildrenOfType(file, HaxeCallExpression.class)) {
      if (call.getExpression() instanceof HaxeReference reference) {
        PsiElement name = reference.getReferenceNameElement();
        if (name != null
            && name.getTextRange().getStartOffset() >= lineStart
            && name.getTextRange().getEndOffset() <= lineEnd) {
          calls.add(call);
        }
      }
    }
    calls.sort(Comparator.comparingInt(call -> call.getTextRange().getStartOffset()));
    return calls;
  }

  // The base implementation throws AbstractMethodError, and the frontend/backend
  // debugger split calls this eagerly while creating the session DTO — an
  // unimplemented title breaks session initialization, not just the popup.
  @Override
  public String getPopupTitle() {
    return HaxeBundle.message("hxcpp.debugger.smart.step.into.title");
  }

  @Override
  public void startStepInto(@NotNull Variant variant) {
    process.stepIntoFunction(variant.className, variant.functionName);
  }

  @Override
  public void startStepInto(@NotNull Variant variant, XSuspendContext context) {
    startStepInto(variant);
  }

  static final class Variant extends XSmartStepIntoVariant {
    private final String className;
    private final String functionName;
    private final @Nullable TextRange highlightRange;

    Variant(String className, String functionName, @Nullable TextRange highlightRange) {
      this.className = className;
      this.functionName = functionName;
      this.highlightRange = highlightRange;
    }

    @Override
    public String getText() {
      // short label: "Target.combine", not the full dotted package path
      String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
      return simpleClassName + "." + functionName;
    }

    @Override
    public Icon getIcon() {
      return AllIcons.Nodes.Method;
    }

    @Override
    public @Nullable TextRange getHighlightRange() {
      return highlightRange;
    }
  }
}
