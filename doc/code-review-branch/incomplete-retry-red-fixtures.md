# Red fixtures for the incomplete-argument retry

Failing test fixtures for the planned fix flagged in
`CallExpressionArgumentModel.incomplete` (src/main/java/com/intellij/plugins/
haxe/model/evaluator/callexpression/CallExpressionArgumentModel.java): re-run
an argument whose evaluation was clipped (recursion guard / in-flight budget,
see HaxeCallExpressionEvaluatorCacheService) once the call's type parameters
are bound, using the resolved parameter type as the expected-type hint.

Test vehicle: `HaxeUntypedParameterInlayTest`, fixtures in
`src/test/resources/testData/inlay/haxe.untyped.parameter.type/`. Each
fixture's markers assert the type the Haxe compiler actually infers
(verified with haxe 4.3.7 `$type` probes). All three kept fixtures now
PASS; the per-fixture notes below record how each was diagnosed and fixed.

## Kept fixtures

### DeepChainExhaustsInFlightBudget.hx — PASSING

Fourteen untyped-parameter functions, each body calling the next
(`f1(v1){f2(v1);}` ... `f14`), `main` calling `f1(1)`. Declared
deepest-first so the first parameter the inlay pass evaluates (`v14`) must
follow the whole chain of call sites in one query.

Instrumented diagnosis showed the original analysis above the line was
wrong in one detail: the in-flight budgets never tripped. The limiter was
`HaxeUntypedParameterInference`'s probe chain DEPTH cap (8): the call-site
descent toward main's literal was cut after 8 hops, so `v9`..`v14` (needing
9-14 hops) returned null. The dirty hole-cache entries stored under the
clipped descent were then served forever, tainting every later probe so
intermediate bindings never cached.

Fixed by replacing the depth cap with a WORK budget per top-level probe
(64 argument evaluations across the whole chain - same worst case as the
old 8 sites x 8 levels, but a linear chain now descends up to 64 hops), and
by the cache service's top-level dirty-entry refresh (one attempt per entry
per settled-binding stamp).

- Compiler-verified: `$type` on f1/f9/f14 all report `(v : Int) -> Void`.
- Now: all fourteen parameters get `:Int` hints.

### HoleEvaluationRecursionSameArgumentTwice.hx — PASSING

`use(w) { pair(w, w); }` with `pair<T>(a:T, b:T):T` and `use(1)` in main.
Typing `w` hole-evaluates `pair(w, w)` with one slot holed; the sibling
argument is `w` itself, so evaluating it re-enters the very question being
answered. The recursion guard clips that evaluation (argument recorded
incomplete/Unknown), `T` never receives an informative binding, and the
hint surfaces the callee's raw type parameter.

- Compiler-verified: `$type(use)` reports `(w : Int) -> Void` (monomorph
  cluster bound at main's `use(1)`).
- Now: the hint shows `:Int` (previously `:T` — a type parameter from
  `pair`'s scope, meaningless inside `use`).

### GenericParameterBindsHoleFromOtherArgument.hx — PASSING

`use(w) { pair(w, 1); }`, `pair<T>(a:T, b:T):T`, and NO call site for
`use`. The literal second argument binds `T = Int` inside the hole
evaluation, so the resolved type of the holed parameter `a:T` is `Int` —
exactly the "resolved parameter type once type parameters are bound" the
retry is meant to apply. Nothing is even recursion-clipped here; the bound
resolver is simply never applied to the hole's answer.

- Compiler-verified: `$type(use)` reports `(w : Int) -> Void`.
- Now: the hint shows `:Int` (previously `:T`).

The last two fail identically on the surface (`T` vs `Int`) but pin
different fix behaviours: in the third, the binding for `T` is available
inside the same hole evaluation (apply the resolver); in the second, `T` is
NOT locally bindable (both slots are the hole's own argument) and the right
answer must come from `use`'s own call site — the retry must not let the
unresolved `T` win over it.

## Attempted shapes that already PASS (fixtures deleted)

- Mutual recursion pure cycle (`ping(a){pong(a);}` / `pong(b){ping(b);}`,
  informative binding only at main's `ping(5)`): both parameters get `:Int`.
- Helper called only inside a lambda whose parameter is typed by a bound
  type parameter (`apply([1,2], n -> helper(n))`): re-entry through the
  lambda body resolves; both `n` and helper's parameter get `:Int`.
- 18-deep RETURN-type chain feeding a generic argument
  (`apply(deepValue(), n -> n)` with `deepValue()` returning through
  g1..g18): return-type chains do not exhaust the in-flight budgets the way
  untyped-parameter chains do; `n` gets `:Int`.
