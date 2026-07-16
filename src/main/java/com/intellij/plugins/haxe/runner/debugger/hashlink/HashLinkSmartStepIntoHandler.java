package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.psi.HaxeCallExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StepInTarget;
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
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

/**
 * Smart step into for HashLink: on a line with several calls (chained
 * {@code a().b()} or nested {@code a(b())}), lists them so the user picks
 * which one to enter. Both the dedicated action (Shift+F7) and the plain Step
 * Into (F7, via {@link #computeStepIntoVariants}) show the chooser; F7 steps
 * plainly when the line has at most one call. The variants come from the adapter's DAP
 * stepInTargets request — the calls on the stopped line, in execution order —
 * and choosing one sends stepIn with that targetId: the adapter plants a temp
 * breakpoint only at the chosen callee's entry, like a run-to-cursor aimed at
 * the method start. An empty variant list makes the platform fall back to a
 * plain step into.
 *
 * Each variant also carries the text range of its call's name identifier on
 * the stopped line (matched through the Haxe PSI by simple name), which is
 * what makes the platform highlight the calls in the editor and let the user
 * Tab between them, like the Java debugger. A target whose call can't be
 * found in the PSI still works — it just isn't highlighted.
 */
class HashLinkSmartStepIntoHandler extends XSmartStepIntoHandler<HashLinkSmartStepIntoHandler.Variant> {
  private final HashLinkDebugProcess process;

  HashLinkSmartStepIntoHandler(HashLinkDebugProcess process) {
    this.process = process;
  }

  // The platform drives this async entry point; compute on the DAP request
  // thread — the sync computeSmartStepVariants must never block the EDT.
  @Override
  public @NotNull Promise<List<Variant>> computeSmartStepVariantsAsync(@NotNull XSourcePosition position) {
    AsyncPromise<List<Variant>> promise = new AsyncPromise<>();
    process.onRequestThread(() -> {
      try {
        promise.setResult(fetchVariants(position));
      } catch (Throwable t) {
        promise.setError(t);
      }
    }, () -> promise.setError("Debug session is shutting down"));
    return promise;
  }

  @Override
  public @NotNull List<Variant> computeSmartStepVariants(@NotNull XSourcePosition position) {
    return fetchVariants(position);
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

  private List<Variant> fetchVariants(XSourcePosition position) {
    List<StepInTarget> targets = process.requestStepInTargets();
    if (targets.isEmpty()) {
      return List.of();
    }
    Project project = process.getSession().getProject();
    List<TextRange> ranges = ReadAction.compute(
      () -> matchCallRanges(targets, callNameElementsInExecutionOrder(project, position)));
    List<Variant> variants = new ArrayList<>(targets.size());
    for (int i = 0; i < targets.size(); i++) {
      variants.add(new Variant(targets.get(i), ranges.get(i)));
    }
    return variants;
  }

  // For each adapter target, the text range of the matching call's NAME
  // identifier on the stopped line, or null when no call with that simple name
  // is (left to) match. BOTH lists are in execution order, so same-named calls
  // on different receivers (`a.reset(b.reset())` — two targets labeled
  // ".reset") consume their own occurrence instead of first-match-wins
  // stealing the leftmost one and swapping the highlights.
  // (package-private, static: exercised directly by tests)
  static List<TextRange> matchCallRanges(List<StepInTarget> targets, List<PsiElement> names) {
    List<TextRange> result = new ArrayList<>();
    List<PsiElement> remaining = new ArrayList<>(names);
    for (StepInTarget target : targets) {
      String label = target.getLabel();
      String simpleName = label.substring(label.lastIndexOf('.') + 1);
      TextRange matched = null;
      for (int i = 0; i < remaining.size(); i++) {
        PsiElement name = remaining.get(i);
        if (name.getText().equals(simpleName)) {
          matched = name.getTextRange();
          remaining.remove(i); // consume, so a repeated callee highlights each occurrence once
          break;
        }
      }
      result.add(matched);
    }
    return result;
  }

  // The name identifiers of the call expressions whose name sits on the
  // position's line, in EXECUTION order — the order the adapter reports
  // targets (bytecode order). A call executes after its receiver and its
  // arguments, so a post-order walk of the PSI matches: `a.reset(b.reset())`
  // yields [b.reset, a.reset], a chain `x.first().second()` yields
  // [first, second]. (package-private, static: exercised directly by tests)
  static List<PsiElement> callNameElementsInExecutionOrder(Project project, XSourcePosition position) {
    List<PsiElement> names = new ArrayList<>();
    PsiFile file = PsiManager.getInstance(project).findFile(position.getFile());
    if (file == null) {
      return names;
    }
    Document document = PsiDocumentManager.getInstance(project).getDocument(file);
    if (document == null || position.getLine() < 0 || position.getLine() >= document.getLineCount()) {
      return names;
    }
    int lineStart = document.getLineStartOffset(position.getLine());
    int lineEnd = document.getLineEndOffset(position.getLine());
    collectCallNamesPostOrder(file, lineStart, lineEnd, names);
    return names;
  }

  private static void collectCallNamesPostOrder(PsiElement element, int lineStart, int lineEnd,
                                                List<PsiElement> out) {
    // skip subtrees that cannot contain the line (keeps the walk cheap-ish)
    TextRange range = element.getTextRange();
    if (range == null || range.getEndOffset() < lineStart || range.getStartOffset() > lineEnd) {
      return;
    }
    for (PsiElement child : element.getChildren()) {
      collectCallNamesPostOrder(child, lineStart, lineEnd, out);
    }
    if (element instanceof HaxeCallExpression call && call.getExpression() instanceof HaxeReference reference) {
      PsiElement name = reference.getReferenceNameElement();
      if (name != null
          && name.getTextRange().getStartOffset() >= lineStart
          && name.getTextRange().getEndOffset() <= lineEnd) {
        out.add(name);
      }
    }
  }

  // The base implementation throws AbstractMethodError, and the frontend/backend
  // debugger split calls this eagerly while creating the session DTO — an
  // unimplemented title breaks session initialization, not just the popup.
  @Override
  public String getPopupTitle() {
    return HaxeBundle.message("hashlink.debugger.smart.step.into.title");
  }

  @Override
  public void startStepInto(@NotNull Variant variant) {
    process.stepIntoTarget(variant.target.getId());
  }

  @Override
  public void startStepInto(@NotNull Variant variant, XSuspendContext context) {
    startStepInto(variant);
  }

  static final class Variant extends XSmartStepIntoVariant {
    private final StepInTarget target;
    private final @Nullable TextRange highlightRange;

    Variant(StepInTarget target, @Nullable TextRange highlightRange) {
      this.target = target;
      this.highlightRange = highlightRange;
    }

    @Override
    public String getText() {
      return target.getLabel();
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
