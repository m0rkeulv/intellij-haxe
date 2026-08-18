# How the Haxe compiler types unannotated parameters

Research for the planned call-site down-pass ("P3" in
doc/resolver-failure-caching.md): what the compiler actually does, so the
plugin's inference can follow the same precedence. Sources: the manual's
type-system chapters and the compiler sources
(src/typing/typeloadFunction.ml, src/context/typecore.ml,
src/core/tUnification.ml on the development branch).

## The mechanism: monomorphs bound by first unification

An unannotated parameter is created as a MONOMORPH (`Unknown<n>`, an open
type variable). "Whenever a type other than Dynamic is unified with a
monomorph, that monomorph becomes that type" — the first unification wins
and is permanent; there is no backtracking and no widening afterwards.

The typing order for a function (typeloadFunction.ml):

1. The signature is loaded; unannotated parameters become monomorphs.
2. The BODY is typed with the parameters in scope. Every use of a
   parameter unifies its monomorph: arithmetic binds numeric types, calls
   through it bind a function type, field access accumulates STRUCTURAL
   constraints.
3. After the body, `safe_mono_close` runs `Monomorph.close` over the
   function's monomorphs (tUnification.ml):
   - already bound: kept;
   - a single accumulated type constraint: bound to it;
   - structural constraints: bound to a CLOSED anonymous type built from
     the used fields;
   - no constraints: LEFT OPEN (bound to Dynamic only in untyped mode).
4. A parameter still open after closing is bound later by ordinary
   unification at the FIRST TYPED CALL SITE - and every subsequent call
   must unify with that binding or error. This is the documented
   order-dependence of Haxe inference: `f(1); f("s")` errors at the
   second call.

So the precedence is: BODY USAGE first, structural closing second, call
sites LAST - and only for what the body left open.

## Recursive calls: the compiler's documented weak spot

The manual's inference-limitations page: "If a function calls itself
recursively while its type is not completely known yet, type inference
may infer an incorrect and overly specialized type." Recursive call sites
DO participate in binding (they are typed mid-body while the monomorphs
are open), and the manual itself flags the result as potentially wrong.

Implication for the plugin: excluding a method's own recursive call sites
from usage-based inference (HaxeExpressionEvaluator.referenceSearch) is a
DELIBERATE deviation that side-steps a documented compiler weakness - it
cannot produce a type the compiler would reject, only avoid
over-specialized ones the manual warns about.

## Implications for the down-pass (P3)

- Pushing call-site argument types into parameters is legitimate ONLY for
  parameters the body leaves unconstrained. Body-derived types take
  precedence; a call-site type that conflicts with body usage is the
  compiler's error case, not a better answer.
- "First call site wins" is the compiler rule, but the compiler's typing
  order (on-demand, module-load dependent) is not reproducible in the
  IDE. Pragmatic mirror: prefer the body-derived type, else the first
  informative call site in declaration-distance order (which is what the
  existing referenceSearch sort already yields), and treat conflicting
  call sites as a unification the annotator may flag rather than silently
  pick from.
- The plugin's current usage-search actually reverses the compiler's
  precedence (it consults call sites eagerly). In well-typed code the two
  agree - body usage and call arguments unify - which is why this rarely
  shows. A correct P3 keeps that agreement by construction: body first,
  call sites as the fallback source.
- Structural closing (field-usage -> closed anonymous type) is the
  compiler's answer for body-only-used parameters; the evaluator's
  body-usage inference approximates this and should remain the primary
  source.
