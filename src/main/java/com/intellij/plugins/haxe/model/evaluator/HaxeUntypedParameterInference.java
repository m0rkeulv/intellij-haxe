package com.intellij.plugins.haxe.model.evaluator;

import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeTypeParameterDeclaration;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator.*;

/**
 * Type inference for method parameters without a type tag, ordered like the compiler's monomorph binding.
 * Usage inside the BODY binds first; argument types at CALL SITES only fill parameters the body leaves open.
 * A conflict between the two is the compiler's error case, so the body-derived type is never overridden.
 */
public final class HaxeUntypedParameterInference {

  // limits how many call sites the probe evaluates before giving up; the
  // first informative site binds (mirroring the compiler's first-typed-call
  // rule as closely as IDE typing order allows)
  private static final int MAX_PROBED_CALL_SITES = 8;

  // a probed argument can itself be an untyped parameter, whose own probe
  // continues the chain until some call site finally passes a concrete
  // value; the cap bounds that chain when call graphs are deep or cyclic
  private static final int MAX_PROBE_CHAIN_DEPTH = 8;

  private static final ThreadLocal<MutableInt> probeChainDepth = ThreadLocal.withInitial(MutableInt::new);

  private static final RecursionGuard<PsiElement>
    callSiteProbeGuard = RecursionManager.createGuard("haxeUntypedParameterCallSiteProbe");

  // Memo of probe outcomes, valid until the next code change, including clean misses: probing chains
  // into argument evaluations that can cycle across methods, and without the
  // memo every query re-runs the whole probe. Only UNTAINTED outcomes are
  // stored (certainty rule) - a result shaped by a cut or prevention must
  // recompute until a clean one lands. Cleared with the evaluator caches.
  private static final Map<HaxeParameter, Optional<ResultHolder>> bindingCache = new ConcurrentHashMap<>();

  public static void clearCaches() {
    bindingCache.clear();
  }

  private HaxeUntypedParameterInference() {
  }

  public static @Nullable ResultHolder inferMethodParameterType(@NotNull HaxeParameter parameter,
                                                                @NotNull HaxeExpressionEvaluatorContext context,
                                                                @NotNull HaxeGenericResolver resolver) {
    // a settled binding (clean body-derived or call-site type from an earlier
    // query since the last code change) answers directly; without it, a deep query would
    // re-run the body search inside guards where its own usage walk
    // truncates and return Unknown for a parameter the signature shows typed
    Optional<ResultHolder> settled = bindingCache.get(parameter);
    if (settled != null && settled.isPresent()) return settled.get();

    long taintMark = HaxeEvaluationTaint.mark();
    ResultHolder bodyDerived = bodyDerivedType(parameter, context, resolver);
    if (bodyDerived != null) {
      if (!HaxeEvaluationTaint.taintedSince(taintMark) && isInformative(bodyDerived)) {
        bindingCache.put(parameter, Optional.of(bodyDerived));
      }
      return bodyDerived;
    }
    return callSiteDerivedType(parameter);
  }

  /** Body usage: the compiler's primary binding source. */
  private static @Nullable ResultHolder bodyDerivedType(HaxeParameter parameter,
                                                        HaxeExpressionEvaluatorContext context,
                                                        HaxeGenericResolver resolver) {
    HaxeMethod method = PsiTreeUtil.getParentOfType(parameter, HaxeMethod.class);
    if (method == null || method.getBody() == null) return null;
    ResultHolder holder = searchReferencesForType(parameter.getComponentName(), context, resolver, method.getBody());
    if (holder.isUnknown()) return null;
    // a generic call the body feeds this parameter into can answer with the
    // callee's own unbound type parameter; that name means nothing in this
    // method's scope and must not shadow a call-site answer
    if (!typeParametersVisibleFrom(method, holder)) return null;
    return holder;
  }

  /** True when every type parameter the type carries is declared by the method itself or an enclosing class. */
  private static boolean typeParametersVisibleFrom(@NotNull HaxeMethod method, @NotNull ResultHolder holder) {
    SpecificTypeReference type = holder.getType();
    if (type instanceof SpecificHaxeClassReference classReference) {
      if (classReference.getHaxeClass() instanceof HaxeTypeParameterDeclaration typeParameter) {
        HaxeNamedComponent owner = typeParameter.getOwner();
        return owner != null && PsiTreeUtil.isAncestor(owner, method, false);
      }
      for (ResultHolder specific : classReference.getSpecifics()) {
        if (!typeParametersVisibleFrom(method, specific)) return false;
      }
    }
    if (type instanceof SpecificFunctionReference function) {
      for (HaxeArgument argument : function.getArguments()) {
        if (!typeParametersVisibleFrom(method, argument.getType())) return false;
      }
      return typeParametersVisibleFrom(method, function.getReturnType());
    }
    return true;
  }

  /**
   * The down-pass: the argument EXPRESSION at a call site is evaluated
   * directly - never the callee's call context - because an argument's type
   * cannot depend on the callee's parameter types. That is what keeps this
   * source free of the call-evaluation re-entry the hole contexts manage.
   * Cross-method cycles (m probes its caller c, whose parameter probes c's
   * call sites inside m) are cut by the guard, tainting like any prevention.
   */
  public static @Nullable ResultHolder callSiteDerivedType(@NotNull HaxeParameter parameter) {
    HaxeMethod method = PsiTreeUtil.getParentOfType(parameter, HaxeMethod.class);
    if (method == null) return null;
    // overload selection owns argument typing for overloaded methods
    HaxeMethodModel model = method.getModel();
    if (model != null && (model.hasModifier(HaxePsiModifier.OVERLOAD) || !model.getOverloadsFromMeta().isEmpty())) return null;
    HaxeComponentName methodName = method.getComponentName();
    if (methodName == null) return null;
    int parameterIndex = parameterIndex(parameter);
    if (parameterIndex < 0) return null;

    Optional<ResultHolder> cached = bindingCache.get(parameter);
    if (cached != null) return cached.orElse(null);

    // Probing must stay out of deep evaluation towers: inside a
    // call-context compute the probe's fan-out (usage search + argument
    // evaluations) multiplies every level, and a gate-miss must TAINT so no
    // consumer freezes a judgment that a later, probed query would
    // contradict. Cheap queries (an inlay provider evaluating a
    // declaration) never have a call compute in flight and may probe. The
    // other exception is a probe running under ANOTHER probe: a probed
    // argument that is itself an untyped parameter continues the chain
    // toward a concrete call site, bounded by the chain cap.
    int chainDepth = probeChainDepth.get().intValue();
    boolean insideProbeChain = chainDepth > 0;
    if ((HaxeCallExpressionEvaluatorCacheService.anyComputeInFlight() && !insideProbeChain)
        || chainDepth >= MAX_PROBE_CHAIN_DEPTH) {
      HaxeEvaluationTaint.taint();
      return null;
    }

    long probeMark = HaxeEvaluationTaint.mark();
    ProbeOutcome outcome = HaxeEvaluationTaint.computeOrTaint(callSiteProbeGuard, parameter, false, () -> {
      MutableInt depth = probeChainDepth.get();
      depth.increment();
      try {
        return probeCallSites(methodName, parameterIndex);
      } finally {
        depth.decrement();
      }
    });
    if (outcome == null) return null;
    // a binding is trusted when ITS argument evaluated clean; a MISS is only
    // trusted when the whole probe (search included) was clean - a truncated
    // search may simply not have seen the informative site yet
    boolean cacheable = outcome.binding() != null
                        ? outcome.bindingEvaluatedClean()
                        : !HaxeEvaluationTaint.taintedSince(probeMark);
    if (cacheable) {
      bindingCache.put(parameter, Optional.ofNullable(outcome.binding()));
    }
    return outcome.binding();
  }

  private record ProbeOutcome(@Nullable ResultHolder binding, boolean bindingEvaluatedClean) {}

  private static @NotNull ProbeOutcome probeCallSites(HaxeComponentName methodName, int parameterIndex) {
    // self-recursive call sites are already filtered out by referenceSearch
    List<PsiReference> callSites = referenceSearch(methodName, (PsiElement)null);
    int probed = 0;
    for (PsiReference callSite : callSites) {
      if (probed >= MAX_PROBED_CALL_SITES) break;
      HaxeExpression argument = argumentAt(callSite, parameterIndex);
      if (argument == null) continue;
      probed++;
      long argumentMark = HaxeEvaluationTaint.mark();
      ResultHolder argumentType = evaluateWithRecursionGuard(argument).result;
      boolean clean = !HaxeEvaluationTaint.taintedSince(argumentMark);
      if (isInformative(argumentType)) return new ProbeOutcome(argumentType, clean);
    }
    return new ProbeOutcome(null, false);
  }

  /**
   * The argument expression at the given parameter index, when the usage is
   * a call shape whose arguments map 1:1 onto parameters. Qualified calls
   * are skipped: for a static method a dotted usage may be a `using`
   * static-extension call, which shifts every index by the receiver.
   */
  private static @Nullable HaxeExpression argumentAt(PsiReference callSite, int parameterIndex) {
    if (!(callSite.getElement() instanceof HaxeExpression element)) return null;
    if (!(element.getParent() instanceof HaxeCallExpression call)) return null;
    if (call.getExpression() != element) return null;
    HaxeCallExpressionList expressionList = call.getExpressionList();
    if (expressionList == null) return null;
    List<HaxeExpression> arguments = expressionList.getExpressionList();
    return parameterIndex < arguments.size() ? arguments.get(parameterIndex) : null;
  }

  /** A binding must be a settled concrete type: the caller's type parameters cannot name the callee's. */
  private static boolean isInformative(@Nullable ResultHolder type) {
    return type != null
           && !type.isUnknown()
           && !type.isOrContainsTypeParameters()
           && type.isCacheable();
  }

  private static int parameterIndex(HaxeParameter parameter) {
    if (!(parameter.getParent() instanceof HaxeParameterList parameterList)) return -1;
    return parameterList.getParameterList().indexOf(parameter);
  }
}
