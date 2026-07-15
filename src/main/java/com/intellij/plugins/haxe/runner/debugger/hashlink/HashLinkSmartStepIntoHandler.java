package com.intellij.plugins.haxe.runner.debugger.hashlink;

import com.intellij.icons.AllIcons;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.StepInTarget;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler;
import com.intellij.xdebugger.stepping.XSmartStepIntoVariant;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

/**
 * Smart step into (Shift+F7) for HashLink: on a line with several calls
 * (chained {@code a().b()} or nested {@code a(b())}), lists them so the user
 * picks which one to enter. The variants come from the adapter's DAP
 * stepInTargets request — the calls on the stopped line, in execution order —
 * and choosing one sends stepIn with that targetId: the adapter plants a temp
 * breakpoint only at the chosen callee's entry, like a run-to-cursor aimed at
 * the method start. An empty variant list makes the platform fall back to a
 * plain step into.
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
        promise.setResult(fetchVariants());
      } catch (Throwable t) {
        promise.setError(t);
      }
    });
    return promise;
  }

  @Override
  public @NotNull List<Variant> computeSmartStepVariants(@NotNull XSourcePosition position) {
    return fetchVariants();
  }

  private List<Variant> fetchVariants() {
    return process.requestStepInTargets().stream().map(Variant::new).toList();
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

    Variant(StepInTarget target) {
      this.target = target;
    }

    @Override
    public String getText() {
      return target.getLabel();
    }

    @Override
    public Icon getIcon() {
      return AllIcons.Nodes.Method;
    }
  }
}
