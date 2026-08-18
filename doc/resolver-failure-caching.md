# Resolver and evaluator caching: the completeness rules

How the plugin decides what type-inference and resolve results may be
cached. File references are to `src/main/java/com/intellij/plugins/haxe/`.

## The problem being solved

Type evaluation and reference resolution are deeply recursive and cut
their own cycles with `RecursionManager` guards. A guard-truncated
computation substitutes a fallback (usually Unknown or an empty result)
for the aborted branch, and the final result carries no trace of the cut.
Such a result is only valid for the exact evaluation stack that produced
it — on a different entry path the cut branch is available and the answer
differs. Caching one freezes a path-dependent artifact as if it were the
truth: inference silently degrades, well-typed references show as
unresolved, and results differ between recomputations.

The rules below make truncation observable and keep every cache limited
to results that are COMPLETE: computed with no truncation on the current
stack and built only from complete data.

## The taint counter (`model/evaluator/HaxeEvaluationTaint`)

A per-thread counter bumped whenever truncated data is observed:

- a guard prevention fired, or a memoized value from an earlier prevented
  cycle was served (`computeOrTaint` detects both);
- a guard-dirty cached call evaluation was served (see below);
- a computation declares itself unreliable (e.g. the restricted resolve
  returning empty, extension-method lookup with an unknown receiver).

Caching boundaries bracket their computation with `mark()` /
`taintedSince()` and refuse to cache when the counter moved. The platform
stamp (`StackStamp.mayCacheNow()`) only sees preventions on the current
stack; the taint counter additionally carries truncation that crosses
cache boundaries.

Guard sites whose computations produce type/resolve data route through
`computeOrTaint`; guards protecting navigation/UI-only computations stay
on plain platform calls — their truncation never reaches a caching
decision, and tainting there would only block caching in contexts that
cannot shape evaluation results.

## Expression cache (`HaxeExpressionEvaluatorCacheService`)

A result is cached only when the platform stamp is clean AND the taint
counter did not move. Successes and failures alike; a failure
additionally requires guard-depth zero (`insideGuardedComputation()`
false), because inside a guarded computation a result can be shaped by
the held guard keys without any prevention firing — the same element can
evaluate to Unknown there and to a real type at top level.

## Call-expression cache (`HaxeCallExpressionEvaluatorCacheService`)

Deliberately NOT stamp-gated: this cache is load-bearing for termination,
not just speed — resolving one reference can evaluate a call whose
arguments resolve further references, and cache hits are what stop those
chains from growing. Each compute is bracketed with taint marks and the
result carries a DIRTY flag; dirty entries — including ones whose return
type, parameter types, or resolver bindings contain unknowns — are still
cached, but serving one taints the reader, so no boundary above judges
laundered data complete. In files whose types never settle the dirty
entries are the only entries, and refusing them would rebuild every call
context on every query.

The compute also bounds re-entry: evaluating an argument can re-evaluate
the same call expression before anything is stored, with fresh resolver
instances no element-keyed guard recognizes as a repeat. Per-thread
in-flight counters cap the nesting (per call, and in total across calls —
the total cap is also the stack-overflow protection); past a cap the
attempt returns null and taints, like a fired prevention. The total
counter doubles as the signal `HaxeUntypedParameterInference` uses to
keep call-site probing out of deep evaluation. The measured calibration
lives with the constants in `HaxeCallExpressionEvaluatorCacheService`.

## Resolver re-entry (`lang/psi/HaxeResolver` + `HaxeResolveFrames`)

Resolving a reference can evaluate expressions that CONTAIN that same
reference (the chain and enum-hint checks evaluate scope statements).
Left to the platform, `ResolveCache`'s recursion guard would truncate the
inner resolve to empty and a well-typed local would transiently look
unresolved. Instead, re-entry is detected on the full-pipeline frame and
diverted to a RESTRICTED pipeline — the check list minus the two
expression-evaluating checks (`checkIsChain`, `checkEnumMemberHints`) —
so tree-walk still resolves locals and members correctly. Restricted
results are never cached, and an empty one taints the thread.
(`checkElementUsage` also evaluates expressions but stays in the
restricted pipeline: untyped-parameter inference has no other source; the
call-evaluation cycle it can enter is cut at the call cache instead.)

## Negative resolves and ResolveCache (the certainty rule)

A miss is only cacheable when it is CERTAIN:

- A clean miss (pipeline exhausted, taint counter unmoved) returns
  `EMPTY_LIST` and is cached normally — a definitively-broken reference
  must not re-resolve on every query.
- An uncertain miss (taint moved during the compute) must not be frozen
  until the next code change: `doResolve` suppresses the platform's cache write via
  `HaxeResolveFrames.suppressCacheWrite`, and the resolve recomputes on
  the next query.

`ResolveCache` stays the one resolve cache; the resolver never returns
null and stores nothing itself. The suppression works through the
platform's own frame mechanics: `prohibitResultCaching(key)` marks only
frames above the key's frame, and a marked frame propagates the bumped
reentrancy count outward when it pops — so the gate frame that
`HaxeResolveFrames` pushes AROUND `resolveWithCaching` is the one key
that puts `ResolveCache`'s frame on the marked side, making its
`mayCacheNow()` false. The bump dies at the gate on exit, so enclosing
evaluations are unaffected. The compiler-blueprint fallback stays outside
the cache entirely, so a blueprint arriving later is never shadowed by a
cached empty.

## Taint polarity: cache gating yes, diagnostics no

The taint signal over-approximates ("some truncation happened beneath
this window"), which is the right polarity for cache gating — a false
positive only misses a cache write. It is the wrong polarity for
diagnostics: most preventions are benign (typedef unwrap cycles, repeat
resolution) and do not make the computed type wrong, so annotator errors
are never suppressed on taint. Unreliable data is a recomputation
concern, not a reporting one.

## Test enforcement

- Both test bases opt out of `RecursionManager`'s
  assert-on-prevention/missed-cache modes: type inference fires
  preventions by design, and the gate's cache suppression is exactly a
  "missed cache" the assertion would otherwise report. In production the
  suppression is silent.

## Future work

- The bounded down-pass exists (`HaxeUntypedParameterInference`: body
  usage first, call-site argument types fill what the body leaves open).
  Remaining: candidate-based argument alignment (treating an untyped
  parameter as implicitly generic instead of leaving it open when call
  sites disagree or pass foreign type parameters), which would let the
  call cache shed dirty entries.
- Consolidate the two expected-type implementations
  (`HaxeResolveChecks.findParentAssignType`'s expected-type mode and
  `HaxeExpressionEvaluatorHandlers.findExpectedTypeForUnify`) into one
  shared home.
