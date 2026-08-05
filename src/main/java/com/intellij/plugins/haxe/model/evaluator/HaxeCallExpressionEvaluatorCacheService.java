package com.intellij.plugins.haxe.model.evaluator;

import com.intellij.plugins.haxe.lang.psi.HaxeCallExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.model.evaluator.callexpression.HaxeCallExpressionContext;
import com.intellij.plugins.haxe.model.evaluator.callexpression.HaxeCallExpressionContextContainer;
import com.intellij.plugins.haxe.model.evaluator.callexpression.HaxeCallExpressionEvaluation;
import com.intellij.plugins.haxe.model.type.HaxeGenericResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import lombok.CustomLog;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.plugins.haxe.model.evaluator.callexpression.HaxeCallExpressionUtil.createContextForMethodCall;

/**
 * Avoids unnecessary re-evaluation of callExpressions
 */
@CustomLog
public class HaxeCallExpressionEvaluatorCacheService  {

  private volatile  Map<CallExpressionEvaluationKey, HaxeCallExpressionEvaluation> cacheMap = new ConcurrentHashMap<>();
  // usage-based parameter inference evaluates the call with a HOLE at the
  // argument being typed, so those evaluations key on the hole index too
  private volatile Map<CallExpressionHoleKey, HoleEvaluation> holeCacheMap = new ConcurrentHashMap<>();
  public static boolean skipCaching = false;// just convenience flag for debugging

  // A compute can arrive back at its own key before anything is stored:
  // evaluating an argument resolves references whose expected-type walk
  // re-evaluates the SAME call expression, with fresh resolver instances so
  // no element-keyed recursion guard ever sees a repeat. BOUNDED re-entry is
  // a real inference pattern - an argument's expected type comes from
  // evaluating its own enclosing call, and overload selection with
  // function-valued arguments nests that several levels (more than 3, at
  // most 8 in the extern-overload fixtures). Past the cap the cut acts like
  // a fired prevention: null result, thread tainted.
  //
  // Correctness does NOT depend on these caps (the suite is green with both
  // disabled); their roles differ and both values are measured, not guessed:
  //
  // PER_KEY is the correctness floor. Legitimate same-key nesting in the
  // fixtures needs more than 2 and at most 4 (extern overloads with
  // function-valued arguments); 8 doubles that margin and never fires on
  // the recursion-heavy ArraySort fixture at all.
  //
  // TOTAL is the performance knob, and its curve is U-SHAPED on that
  // fixture: ~21s at 8, ~34s at 12, ~8.5s at 16, ~9s at 24, ~12s at 32,
  // ~20s uncapped. Too high wastes time in redundant deep circling (inner
  // re-evaluations only see degraded inputs the outer, shallower iteration
  // is already improving on); too low aborts computes before they produce
  // storable results, so everything comes back dirty and consumers re-query
  // in a recompute storm. It is also the stack-overflow fuse: an SOE mid
  // unwind corrupts RecursionManager's frame bookkeeping ("Map size
  // changed" errors), which degrades far worse than a tainted cut.
  private static final int MAX_IN_FLIGHT_PER_KEY = 8;
  private static final int MAX_IN_FLIGHT_TOTAL = 16;
  private static final ThreadLocal<Map<Object, Integer>> inFlight = ThreadLocal.withInitial(HashMap::new);
  private static final ThreadLocal<int[]> inFlightTotal = ThreadLocal.withInitial(() -> new int[1]);


  public static @Nullable HaxeCallExpressionEvaluation cachedHaxeCallExpressionEvaluation(HaxeMethod method, HaxeCallExpression callExpression) {

    HaxeCallExpressionEvaluatorCacheService service = method.getProject().getService(HaxeCallExpressionEvaluatorCacheService.class);
    return  service.callExpressionCachedEvaluation(method, callExpression);

  }


  public @Nullable HaxeCallExpressionEvaluation callExpressionCachedEvaluation(HaxeMethod method, HaxeCallExpression callExpression) {

    if(skipCaching){
      HaxeCallExpressionContextContainer contextContainer = createContextForMethodCall(callExpression, method);
      return contextContainer.evaluateContexts();
    }

    CallExpressionEvaluationKey key = new CallExpressionEvaluationKey(method, callExpression);
    HaxeCallExpressionEvaluation cached = cacheMap.get(key);
    if (cached != null) {
      if (cached.isComputedWithGuardFired()) {
        HaxeEvaluationTaint.taint();
      }
      return cached;
    }

    Map<Object, Integer> inProgress = inFlight.get();
    int[] total = inFlightTotal.get();
    int depth = inProgress.merge(key, 1, Integer::sum);
    total[0]++;
    if (depth > MAX_IN_FLIGHT_PER_KEY || total[0] > MAX_IN_FLIGHT_TOTAL) {
      releaseInFlight(inProgress, key);
      total[0]--;
      HaxeEvaluationTaint.taint();
      return null;
    }
    try {
      // Taint marks, not the platform stamp: mayCacheNow() throws under the
      // test-mode assertOnMissedCache, and preventions routinely fire beneath
      // this compute. The mark also inherits dirtiness from nested dirty cache
      // hits, so the flag propagates transitively.
      long taintMark = HaxeEvaluationTaint.mark();
      HaxeCallExpressionContextContainer contextContainer = createContextForMethodCall(callExpression, method);
      HaxeCallExpressionEvaluation evaluate = contextContainer.evaluateContexts();
      if(evaluate == null) return null;
      evaluate.setComputedWithGuardFired(isDirty(taintMark, evaluate));

      // Deliberately NOT gated on RecursionManager.markStack()/mayCacheNow():
      // this cache is load-bearing for termination, not just speed. Deep
      // isReferenceTo/resolve storms (move refactorings) recurse through
      // findParentAssignType -> call evaluation chains, and the cache hit -
      // including one computed above a fired recursion guard - is what flattens
      // the recursion; gating it overflowed the stack. The PSI-change listener
      // bounds the staleness; the DIRTY flag travels with the entry so
      // consumers taint instead of trusting it as complete. Unknown-containing
      // entries are stored too - in files whose types cannot settle (untyped
      // recursive helpers) they are the ONLY entries, and refusing them means
      // every reference rebuilds the same call context every pass.
      if(evaluate.isValid() && evaluate.isCompleted()) {
        HaxeCallExpressionContext context = contextContainer.getContext();
        if(context != null && context.canCache) {
          cacheMap.put(key, evaluate);
        }
      }

      return evaluate;
    } finally {
      releaseInFlight(inProgress, key);
      total[0]--;
    }
  }

  private static void releaseInFlight(Map<Object, Integer> inProgress, Object key) {
    inProgress.merge(key, -1, (a, b) -> a + b <= 0 ? null : a + b);
  }

  public static @Nullable HoleEvaluation cachedHoleEvaluation(HaxeMethod method, HaxeCallExpression callExpression, int holeArgumentIndex) {
    HaxeCallExpressionEvaluatorCacheService service = method.getProject().getService(HaxeCallExpressionEvaluatorCacheService.class);
    return service.holeEvaluation(method, callExpression, holeArgumentIndex);
  }

  private @Nullable HoleEvaluation holeEvaluation(HaxeMethod method, HaxeCallExpression callExpression, int holeArgumentIndex) {
    if (skipCaching) {
      return computeHoleEvaluation(method, callExpression, holeArgumentIndex);
    }

    CallExpressionHoleKey key = new CallExpressionHoleKey(method, callExpression, holeArgumentIndex);
    HoleEvaluation cached = holeCacheMap.get(key);
    if (cached != null) {
      if (cached.dirty()) {
        HaxeEvaluationTaint.taint();
      }
      return cached;
    }

    Map<Object, Integer> inProgress = inFlight.get();
    int[] total = inFlightTotal.get();
    int depth = inProgress.merge(key, 1, Integer::sum);
    total[0]++;
    if (depth > MAX_IN_FLIGHT_PER_KEY || total[0] > MAX_IN_FLIGHT_TOTAL) {
      releaseInFlight(inProgress, key);
      total[0]--;
      HaxeEvaluationTaint.taint();
      return null;
    }
    try {
      HoleEvaluation result = computeHoleEvaluation(method, callExpression, holeArgumentIndex);
      boolean storable = result != null
        && result.evaluation().isValid()
        && result.evaluation().isCompleted()
        && result.canCache();
      if (storable) {
        holeCacheMap.put(key, result);
      }
      return result;
    } finally {
      releaseInFlight(inProgress, key);
      total[0]--;
    }
  }

  private static @Nullable HoleEvaluation computeHoleEvaluation(HaxeMethod method, HaxeCallExpression callExpression, int holeArgumentIndex) {
    long taintMark = HaxeEvaluationTaint.mark();
    HaxeCallExpressionContextContainer container = createContextForMethodCall(callExpression, method, holeArgumentIndex);
    HaxeCallExpressionEvaluation evaluate = container.evaluateContexts();
    if (evaluate == null) return null;
    HaxeCallExpressionContext context = container.getContext();
    boolean staticExtension = context != null && context.isStaticExtension;
    boolean canCache = context != null && context.canCache;
    return new HoleEvaluation(evaluate, staticExtension, canCache, isDirty(taintMark, evaluate));
  }

  /** A hole-context evaluation; dirty entries are served with a taint, like the main cache's. */
  public record HoleEvaluation(HaxeCallExpressionEvaluation evaluation, boolean staticExtension, boolean canCache, boolean dirty) {}

  /**
   * Dirty when the compute observed truncation OR the result carries unknown
   * types anywhere. The content check covers what the taint bracket cannot
   * see: an Unknown binding from an argument whose resolve was prevented
   * inside the platform's ResolveCache. A dirty entry is still cached and
   * served, but serving it taints the reader, so no caching boundary judges
   * a failure "complete" on this data.
   */
  private static boolean isDirty(long taintMark, HaxeCallExpressionEvaluation evaluate) {
    return HaxeEvaluationTaint.taintedSince(taintMark) || !noUnknownResolvedValues(evaluate);
  }

  private static boolean noUnknownResolvedValues( HaxeCallExpressionEvaluation evaluate) {
      if(evaluate.getReturnTypeWithoutResolve().containsUnknownTypes()) {
        return false;
      }
      for (ResultHolder parameterType : evaluate.getParameterTypes()) {
        if(parameterType.containsUnknownTypes()) {
          return false;
        }
      }
      return resolverFreeOfUnknowns(evaluate.getCallExpressionResolver())
          && resolverFreeOfUnknowns(evaluate.getCallieResolver());
    }

  private static boolean resolverFreeOfUnknowns(@Nullable HaxeGenericResolver resolver) {
    if (resolver == null) return true;
    return resolver.entries().length == resolver.withoutUnknowns().entries().length;
  }


  public void clearCaches() {
    synchronized(this) {
      holeCacheMap.clear();
      cacheMap.clear();
    }
  }
}

record CallExpressionEvaluationKey(HaxeMethod method, HaxeCallExpression callExpression) {
}

record CallExpressionHoleKey(HaxeMethod method, HaxeCallExpression callExpression, int holeArgumentIndex) {
}