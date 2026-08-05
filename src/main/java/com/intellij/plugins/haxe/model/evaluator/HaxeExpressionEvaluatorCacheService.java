package com.intellij.plugins.haxe.model.evaluator;

import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.plugins.haxe.model.type.HaxeGenericResolver;
import com.intellij.plugins.haxe.model.type.ResultHolder;
import com.intellij.plugins.haxe.model.type.SpecificTypeReference;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator._handle;

/**
 * To avoid unnecessary re-evaluation of elements used by  other expressions (ex. functions without type tags etc)
 * We cache the evaluation result until a Psi change happens, we dont want to cache for longer as ResultHolder
 * contains SpecificTypeReference elements both as the type and as generics and these contain PsiElements
 * that might become invalid
 */
public class HaxeExpressionEvaluatorCacheService  {

  private volatile  Map<EvaluationKey, ResultHolder> cacheMap = new ConcurrentHashMap<>();
  private volatile Map<PsiElement, ResultHolder> methodReturnTypes = new ConcurrentHashMap<>();
  public static boolean skipCaching = false;// just convenience flag for debugging



  public HaxeExpressionEvaluatorCacheService() {
    LowMemoryWatcher.register(() -> {
      clearCaches();
    });
  }

  public @NotNull ResultHolder handleWithResultCaching(@NotNull final PsiElement element,
                                                       @NotNull final HaxeExpressionEvaluatorContext context,
                                                       @Nullable final HaxeGenericResolver resolver) {

    if(skipCaching){
      ResultHolder holder = _handle(element, context, resolver);
      if(holder == null) return SpecificTypeReference.getUnknown(element).createHolder();
      return holder;
    }

    EvaluationKey key = new EvaluationKey(element, resolver == null ? "NO_RESOLVER" : resolver.toCacheString());
    ResultHolder cached = cacheMap.get(key);
    if (cached != null) {
      return cached;
    }

    // The stamp distinguishes COMPLETE results from guard-truncated ones: any
    // recursion guard firing beneath this point makes mayCacheNow() false, and
    // such a result is only valid for this exact evaluation stack. The taint
    // mark covers what the stamp cannot see: a guard-truncated CACHED call
    // evaluation served from another stack's computation. A failure computed
    // with both signals clean genuinely tried every path and is as
    // authoritative as a success - caching it is what keeps broken references
    // from re-running the whole evaluation on every visit.
    RecursionGuard.StackStamp stamp = RecursionManager.markStack();
    long taintMark = HaxeEvaluationTaint.mark();
    ResultHolder holder = _handle(element, context, resolver);
    if (holder == null) return SpecificTypeReference.getUnknown(element).createHolder();
    boolean complete = stamp.mayCacheNow() && !HaxeEvaluationTaint.taintedSince(taintMark);
    if (complete && holder.isCacheable()) {
      boolean isUnknown = holder.isUnknown();
      // success: fully resolved with all typeParameters
      boolean cacheableSuccess = !isUnknown && !holder.containsUnknownOrUnresolvedTypes();
      // failures additionally require guard-depth zero: inside a guarded
      // computation a result can be shaped by held guard keys without any
      // prevention firing, and the same element that fails there may
      // evaluate to a real type at top level (see HaxeEvaluationTaint)
      //failure:  unknown and
      boolean cacheableFailure = isUnknown  && !HaxeEvaluationTaint.insideGuardedComputation();

      if (cacheableSuccess || cacheableFailure) {
        cacheMap.put(key, holder);
      }
    }
    return holder;

  }


  /**
   * Inferred method return types under the certainty rule: a result computed
   * while truncation was observed (a probe gate refusal, a prevention) is
   * served but NOT stored, so a later clean compute can land. A
   * PsiDependentCache here froze the first tower-computed Unknown for the
   * whole tick and starved every later consumer - the return-type inlay and
   * any local initialized from the call.
   */
  public @NotNull ResultHolder methodReturnType(@NotNull PsiElement method, @NotNull Supplier<ResultHolder> compute) {
    ResultHolder cached = methodReturnTypes.get(method);
    if (cached != null) return cached;
    long taintMark = HaxeEvaluationTaint.mark();
    ResultHolder computed = compute.get();
    boolean clean = !HaxeEvaluationTaint.taintedSince(taintMark);
    // clean Unknown inside a guarded computation is still path-dependent
    // (same rule as the expression cache's failure caching)
    boolean unknownInsideGuards = computed.isUnknown() && HaxeEvaluationTaint.insideGuardedComputation();
    if (clean && !unknownInsideGuards && computed.isCacheable()) {
      methodReturnTypes.put(method, computed);
    }
    return computed;
  }

  public void clearCaches() {
    synchronized(this) {
      methodReturnTypes.clear();
      cacheMap.clear();
    }
  }
}

record EvaluationKey( PsiElement element, String evalParamString) {
}