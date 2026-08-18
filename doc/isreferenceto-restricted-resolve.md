# isReferenceTo: can it use a subset of the resolver?

Analysis of whether `HaxeReferenceImpl.isReferenceTo` needs the full resolve
pipeline, or whether a candidate-directed subset can answer correctly.
File references are to `src/main/java/com/intellij/plugins/haxe/`.

## What isReferenceTo answers, and what it runs today

`isReferenceTo(element)` answers one yes/no question: does THIS reference
point at THAT element. It is the per-occurrence filter behind every
`ReferencesSearch` — find-usages, rename, and the evaluator's usage-based
type inference all funnel through it, once per text occurrence of the name.

Today (`lang/psi/impl/HaxeReferenceImpl.isReferenceTo`) it has exactly one
shortcut — a reference with two child references (a dotted chain head) is
rejected without resolving — and otherwise runs `resolveToComponentName()`,
which is the complete `multiResolve` pipeline. The candidate element it was
handed is used only for the final identity comparison. Its KIND is never
consulted, and the kind is exactly what determines which checks could ever
produce it.

## The asymmetry a subset can exploit

Full resolution must find what a reference points to, whatever that is.
isReferenceTo only needs to compare against a KNOWN target. That permits
two kinds of shortcut:

1. **Instant negatives from shape.** If the candidate's kind cannot be
   referenced from the occurrence's syntactic position, the answer is false
   with no resolve at all:
   - candidate is local-scoped (parameter, local var, local function,
     type parameter) and the occurrence lies OUTSIDE the declaring scope —
     `HaxeNamedElementImpl.getUseScope` already bounds the search this way,
     but occurrences inside an enclosing lambda/nested function still get
     full resolves;
   - candidate is local-scoped and the occurrence is the member part of a
     qualified chain (`expr.name`) — locals are never accessed qualified;
   - candidate is a type but the occurrence sits in a position that only
     values can occupy (and vice versa) — partially covered today by the
     chain shortcut.

2. **Kind-directed check subsets.** For a positive answer, only the checks
   that can PRODUCE the candidate's kind need to run — plus, for
   correctness, any earlier check that could claim the same occurrence
   first (the preemption problem, below).

## Which checks can produce which candidate kinds

The `doResolveInner` pipeline, grouped by cost:

| Check family | Cost | Produces |
|---|---|---|
| checkIsTypeParameter, checkIsType, checkIsAlias, checkIsAccessor, checkIsSuperExpression, checkIsNewExpression, checkMacroIdentifier, checkIsFullyQualifiedStatement | cheap | type params, types, import aliases, accessors |
| checkEnumExtractor, checkReferenceInExtractorMatchExpression, checkIsSwitchVar, checkCaptureVar(Reference), **checkByTreeWalk** | cheap (tree walk) | **locals, parameters, local functions, extractor/switch/capture vars, members of enclosing classes** |
| searchInSameFile, checkIsModuleName, checkIsClassName, checkMemberReference, checkIsForwardedName, checkGlobalAlias, checkIsLocalModule | moderate | types, members, forwarded members |
| checkImports (searchInImports/searchInSamePackage) | moderate–expensive | types, static members, enum values via import/using |
| **checkIsChain** | EXPENSIVE (evaluates qualifier) | members reached through an expression's type |
| **checkEnumMemberHints** | EXPENSIVE (expected-type eval) | enum values from assign/argument context |
| **checkElementUsage** | EXPENSIVE (findParentAssignType → call evaluation) | members implied by expected type (anon fields etc.) |

Mapping candidate kinds to the checks that can yield them:

- **Local-scoped kinds** (parameter, local var/function, capture/switch/
  extractor var, type parameter): only the tree-walk family and
  checkIsTypeParameter. None of the three expensive checks can ever
  return one.
- **Types** (class, enum, typedef, abstract, module): the type/import
  family. Not chain, not enum hints, not element usage.
- **Enum values**: checkEnumMemberHints, extractors, switch-on-enum,
  imports, and qualified access — the case that genuinely needs
  expected-type evaluation, as suspected.
- **Instance members**: tree walk (inside own class), checkMemberReference,
  chain for `expr.member` — qualified usages genuinely need typing.
- **Static members**: tree walk / member reference when unqualified inside
  the class; imports/using and class-qualified access otherwise. `using`
  (static extension) is the trap: `value.func()` binds a STATIC through the
  receiver's type, so a static candidate reachable via `using` still needs
  chain-level typing for dotted occurrences.

## The preemption problem, settled by the language's resolution order

Skipping checks cannot cause false negatives — if the true winner comes
from outside subset S, it is not the candidate, and S returning nothing
gives false correctly. The risk is false POSITIVES: S finding the
candidate when a check outside S would have claimed the occurrence first.

The Haxe manual's resolution order
(https://haxe.org/manual/type-system-resolution-order.html) settles this
for local-scoped candidates. For an unqualified identifier the compiler
resolves, in order: keywords/constants; LOCAL VARIABLES (including
parameters); member fields; static extensions; static fields; enum
constructors of imported enums; explicitly imported statics; type names.
Locals outrank everything except keywords, so an accessible local can
never be preempted — in particular, a bare identifier matching an
accessible local is never an enum value, and omitting the enum-hint check
from the local fast path is what the language mandates, not a shortcut.

`checkEnumMemberHints` turns out to be position-gated: its whole body is
conditional on the reference sitting in an enum-value-reference position
inside a switch-case pattern. So it never competes with locals in
expression position (the pipeline's hints-before-tree-walk order is
harmless there) — and pattern positions are the one context where
enum-first is the COMPILER's rule too: in a `case` pattern an identifier
naming an enum constructor is the constructor, and only a capture variable
otherwise. The fast path therefore falls back to the full pipeline for
occurrences in switch-case pattern positions (cheap parent-type test,
rare occurrences) and runs its tree-walk subset everywhere else, where
locals-first is absolute.

Caveat that survives: local accessibility is declaration-ordered (a name
above its `var` does not see it). The fast path inherits whatever
`checkByTreeWalk` does today; verify during implementation.

## Assessment

- The clean, provable win is the **local-scoped fast path**: shape
  negatives (outside scope, qualified occurrence) cost nothing, and the
  positive path needs only the tree-walk family — with the caveat that the
  enum-hint preemption case (bare identifier, assign/argument position,
  enum value and local sharing a name) either runs the hint check or is
  accepted as a divergence. This is also the highest-volume case: the
  evaluator's untyped-parameter inference probes exactly these candidates,
  and each probe currently pays full resolves per occurrence, including
  the expensive checks that can never produce a local.
- **Type candidates** are a plausible second fast path (skip the three
  expensive checks; keep the import/package family) — lower volume, since
  type references resolve through ResolveCache and are cacheable/certain
  far more often.
- **Enum values and instance members cannot be subset** beyond the shape
  negatives; their resolution legitimately depends on expected-type and
  qualifier evaluation.
- Any fast path must run OUTSIDE ResolveCache (its answer is
  candidate-relative, not the reference's general resolution) and must not
  write into it.

## Verification plan for an implementation

- `HaxeRecursiveStdInferenceTest` measures the wall-time effect (the
  ArraySort fixpoint is dominated by exactly these per-occurrence
  resolves).
- The full suite's rename, find-usages, goto-declaration and highlighting
  tests are the correctness gate for divergences.
- A temporary assertion mode that runs both the subset and the full
  pipeline and reports mismatches over the suite would inventory real
  preemption cases before committing to the shortcut.
