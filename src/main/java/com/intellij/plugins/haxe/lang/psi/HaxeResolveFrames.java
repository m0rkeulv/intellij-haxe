package com.intellij.plugins.haxe.lang.psi;

import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.util.Ref;
import com.intellij.plugins.haxe.model.evaluator.HaxeEvaluationTaint;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The three stack frames a resolve runs under, and the protocol each one
 * exists for. All RecursionManager frame geometry lives here so the
 * resolver reads as plain steps.
 *
 * <pre>
 *   cache gate frame            (runCacheGated)
 *     ResolveCache frame        (the platform's resolveWithCaching)
 *       full pipeline frame     (runFullPipeline)
 *         [restricted frame]    (runRestrictedPipeline, re-entry only)
 * </pre>
 *
 * Frame mechanics this design rests on:
 * {@code prohibitResultCaching(key)} marks only the frames strictly ABOVE
 * the key's frame, and a marked frame propagates the bumped reentrancy
 * count outward when it pops ({@code StackFrame.addPrevention} overwrites
 * {@code reentrancyStamp}; {@code afterComputation} restores the count from
 * it). So suppressing the platform's cache write requires a key BELOW its
 * frame — the cache gate — and a prohibit keyed anywhere inside the
 * resolver can never reach it. The bump also dies at the gate frame on
 * exit, leaving enclosing evaluations unaffected.
 */
final class HaxeResolveFrames {

  private final RecursionGuard<PsiElement> fullPipelineGuard = RecursionManager.createGuard("haxeResolveFullPipeline");
  private final RecursionGuard<PsiElement> restrictedPipelineGuard = RecursionManager.createGuard("haxeResolveRestrictedPipeline");
  private final RecursionGuard<PsiElement> cacheGateGuard = RecursionManager.createGuard("haxeResolveCacheGate");

  // Re-entry membership is tracked here, not via RecursionGuard.currentStack():
  // that call walks EVERY guard frame on the thread and allocates a list, and
  // at one check per resolve it dominated editing profiles on recursion-heavy
  // files. The run* brackets push/pop; the checks are O(1) and allocation-free.
  private static final ThreadLocal<Set<HaxeReference>> fullInProgress = identitySet();
  private static final ThreadLocal<Set<HaxeReference>> restrictedInProgress = identitySet();

  private static ThreadLocal<Set<HaxeReference>> identitySet() {
    return ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  /** A full-pipeline resolve of this reference is already running on this thread. */
  boolean fullResolveInProgress(@NotNull HaxeReference reference) {
    return fullInProgress.get().contains(reference);
  }

  /** A restricted resolve of this reference is already running on this thread. */
  boolean restrictedResolveInProgress(@NotNull HaxeReference reference) {
    return restrictedInProgress.get().contains(reference);
  }

  /**
   * Runs the full check pipeline under its frame. memoize=false: this frame
   * must never serve a memoized resolve result — the caller's certainty
   * accounting needs every resolve computed fresh.
   */
  @Nullable
  List<? extends PsiElement> runFullPipeline(@NotNull HaxeReference reference,
                                             @NotNull Computable<List<? extends PsiElement>> pipeline) {
    Set<HaxeReference> inProgress = fullInProgress.get();
    inProgress.add(reference);
    try {
      return HaxeEvaluationTaint.computeOrTaint(fullPipelineGuard, reference, false, pipeline);
    } finally {
      inProgress.remove(reference);
    }
  }

  /**
   * Runs the restricted pipeline under its own frame so second-level
   * re-entry shows on {@link #restrictedResolveInProgress}. The caller's
   * in-progress pre-check keeps this frame's prevention unreachable; the
   * Ref wrapping makes a null return mean prevention rather than a pipeline
   * that found nothing.
   */
  @Nullable
  List<? extends PsiElement> runRestrictedPipeline(@NotNull HaxeReference reference,
                                                   @NotNull Computable<List<? extends PsiElement>> pipeline) {
    Set<HaxeReference> inProgress = restrictedInProgress.get();
    inProgress.add(reference);
    try {
      Ref<List<? extends PsiElement>> computed = restrictedPipelineGuard.doPreventingRecursion(reference, false, () -> Ref.create(pipeline.get()));
      return computed == null ? null : computed.get();
    } finally {
      inProgress.remove(reference);
    }
  }

  /**
   * Runs the platform-cached resolve inside the gate frame, so
   * {@link #suppressCacheWrite} has a key BELOW ResolveCache's frame to
   * prohibit on. The gate cannot actually prevent — same-reference re-entry
   * is diverted before this frame is pushed.
   */
  @Nullable
  List<? extends PsiElement> runCacheGated(@NotNull HaxeReference reference,
                                           @NotNull Computable<List<? extends PsiElement>> cachedResolve) {
    Ref<List<? extends PsiElement>> gated = cacheGateGuard.doPreventingRecursion(reference, false, () -> Ref.create(cachedResolve.get()));
    return gated == null ? null : gated.get();
  }

  /**
   * Makes the enclosing ResolveCache compute skip its cache write (and the
   * IdempotenceChecker comparison that comes with it): marks the platform's
   * frame through the gate key so its {@code mayCacheNow()} sees the bump.
   * Must be called while still inside {@link #runCacheGated}'s computation.
   * Test-mode note: under assertOnMissedCache the platform then throws a
   * CachingPreventedException naming the gate — that is the assertion
   * observing this INTENDED suppression; the test bases opt out.
   */
  void suppressCacheWrite(@NotNull HaxeReference reference) {
    cacheGateGuard.prohibitResultCaching(reference);
  }
}
