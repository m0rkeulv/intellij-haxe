# TODO inventory (all 101 in scope, with suggestions)

Record-only per the assignment: nothing is being fixed now; suggestions are
for a later pass. Grouped by area. Format: `file:line` — gist → suggestion.

## Naked TODOs (say nothing — need an owner decision)

- `lang/psi/stubs/serializers/HaxeClassStubSerializer.java:113` — bare `//TODO`
  above the deserialize `return new HaxeClassStub(...)`. Nothing in context
  hints at intent → ask author; if unreconstructable, delete.
- `model/evaluator/HaxeExpressionEvaluatorHandlers.java:132` — bare `//TODO`
  above `throw new RuntimeException("MLO: inspect")` with the real return
  commented out. Already chipped as its own bug task (restore the return).
- `model/evaluator/HaxeExpressionEvaluatorHandlers.java:297` — the TODO TEXT
  is actually on the following lines and IS meaningful (see evaluator section
  below); only the marker line is bare. Merge marker + text.

## Resolver / references (`lang/psi`) — 20

- `HaxeResolver.java:392` — "skip if contains symbols" before
  `findFile(reference.getText() + ".hx")` → guard: only try the file lookup
  when the reference text is a valid module name (no dots/operators);
  currently a reference like `a+b` could attempt a nonsense filename.
- `HaxeResolver.java:530` — documented HACK for enum-constructor recursion
  with anonymous-structure arguments → legit deferral; keep; candidate for a
  RecursionGuard-based fix when the evaluator is next reworked.
- `HaxeResolver.java:1933` — extractor-match method-call mapping issue,
  "needs testing" → write the test it asks for (extractor match calling a
  function-typed parameter) and either delete the guard or make it a comment
  stating verified behaviour.
- `HaxeResolver.java:2165` — "hackish tmp workaround for overloads" (re-walks
  scope with a flag) → fold into a real overload-selection step when
  overload support is next touched.
- `HaxeResolver.java:2470` — reminder to honour `@:using` on the type in
  using-resolution → real missing feature; belongs with the `using` work.
- `HaxeResolver.java:2525` + `:3150` — twin TODOs saying resolveChain and
  resolveByClassAndSymbol should merge → agreed; that merge is the core of
  any future resolver cleanup; keep both markers until then.
- `HaxeResolver.java:2588` — "clean up (separate members and extension
  methods)" → structural refactor suggestion, valid.
- `HaxeResolver.java:2647` — clearFakePsi placement doubt → revisit with the
  fake-psi mechanism.
- `HaxeResolver.java:2718` — "try to get namedComponent from element" in
  using-expose → small improvement, keep.
- `HaxeResolver.java:3062` — "resolve with resolver?" for function-type
  generic parts → likely yes; needs a test with generic function-type params.
- `HaxeResolverScopeProcessor.java:40` — wants HaxeComponentName-based
  solution for enum-literal elements → structural, keep.
- `HaxeResolverScopeProcessor.java:115` — "figure out if non-ComponentName
  elements are OK in result list" → decide + document the invariant; the
  session's resolver idempotence work (normalizeClassResult) is adjacent.
- `HaxeReferenceImpl.java:202/203` — substitutor doubt in CandidateInfo →
  fine as long as Haxe has no Java-style substitution; downgrade to comment.
- `HaxeReferenceImpl.java:391` — ExprOf typedef hack → legit deferral tied to
  macro-type handling.
- `HaxeReferenceImpl.java:570` — hard-coded "T" → real wart; fix is to read
  the class's actual first type-parameter name.
- `HaxeReferenceImpl.java:634` — duplicate generic-resolver assembly
  ("should not be necessary with both") → measure and remove one path.
- `HaxeReferenceImpl.java:734` — commented-out attempt to produce a resolve
  result for method declarations → either finish (function-reference result)
  or delete the dead commented block.
- `HaxeReferenceImpl.java:1372/1385/1404` — three "Unimplemented" PsiClass
  surface methods that log warnings → deliberate stubs; fine; consider
  removing the log spam guard flag once confirmed nothing needs them.

## Evaluator (`model/evaluator/HaxeExpressionEvaluatorHandlers.java`) — 24

- `:168` — "Develop some fixers" for unify errors → feature wish, keep.
- `:206` — macro identifier could get a preciser type → keep.
- `:244` — "Yo! Eric!! ... resolver coming back as Dynamic when it should be
  String" → ancient upstream note; verify with a test; if unreproducible,
  delete.
- `:297` (+ marker) — switch-case enum-extraction references resolve to
  themselves; "rewrite BNF so it's not a reference" → real grammar-level
  suggestion; record as a candidate BNF change (extractor value as its own
  element, aligning with :886 below).
- `:305` — "cleaner solution" for GenericResolver in enum extractors → same
  cluster as :297/:886.
- `:325` — "add tests for method/function alias" → cheap test to add.
- `:621` — string interpolation makes literal non-constant → correct
  observation; guard `getString(constant)` when text contains interpolation.
- `:676` — "verify this works" (spread of array literal) → add test.
- `:833` — wants resolveWithConstraintCheck → API suggestion, keep.
- `:850` — "Check arguments" in create-constructor fixer → fixer polish.
- `:886` — redo extractor parsing in BNF for ExprArray/ArrayLiteral
  consistency → same BNF cluster as :297.
- `:909` — object-name path in extractor index walk commented out → decide
  whether object extraction is legal Haxe; add test either way.
- `:940` — array access on other types → verify support, then implement or
  delete branch.
- `:1103` — "create rule use first on unknown" for if-unification → ties to
  UnificationRules; keep.
- `:1111` — expected-type propagation into untyped lambda params → real
  improvement wish, keep.
- `:1162` + `:1172` — twin "check if rest param?" → yes: rest params exist
  since 4.2; arguments built here mark isRest=false always. Suggest fix.
- `:1175` — "Add Void if list.size() == 0" → check function-type building
  for zero-arg lambdas; likely already handled elsewhere; verify + delete.
- `:1199` — "cache last element" → getLastExpressionCached already exists on
  the next line; marker looks STALE → delete after confirming.
- `:1203` — eliminate non-value expressions when inferring block value →
  partial (if-without-else filtered); keep for the remaining cases.
- `:1270` — recorded ClassCastException case (constant = abstract class
  decl) → turn into a regression test; the comment preserves the repro.
- `:1309` + `:1349` — twin "make better solution" for extern ArrayAccess
  interface hack → keep, but the duplicated hack itself is a dedup candidate.
- `:1642` — "Maybe track constants in map types" → wish, keep.
- `:1765` — "@TODO: this should be unnecessary when code is working right" →
  fallback re-resolve path; measure whether it ever fires; delete if dead.
- `:1793` — enum-value constructor handled inside call-expression handler →
  agree it belongs in its own handler; structural.
- `:1900` — "resolve the function type return type" → real gap, keep.
- `:2071` — "check if typeParams need to be copied over" → write the test
  (enum value with type params via EnumValue tools).
- `:2118` — canAssign skipped when no annotation holder; "see if we need
  this" → measure; likely correct as-is, rewrite as behaviour comment.
- `:2257` — switch-statement result evaluation "should be properly
  implemented" → known gap, keep.
- `:2351` — cache ArrayAccess/ArrayIterator lookups + string constants →
  easy perf win; suggest static constants + CachedValue.

## Resolve util (`util/HaxeResolveUtil.java`) — 12

- `:487` — recursion detection "breaks tests, should make a RecursionGuard"
  → aligns with platform RecursionManager; good future fix.
- `:570` — "add support for function in ResolveResult" → same theme as
  resolver :905/:921; one work item: function types as resolve results.
- `:611` — "remove?" for-statement branch → decide via coverage: run suite
  with the branch disabled locally at fix time.
- `:661` + `:674` — twin "function literals have no HaxeType → null" → same
  function-type work item.
- `:706` — missing type parameter on `new Map()` inference → real gap;
  test exists? add.
- `:905` — "Function return types not implemented in the resolver yet" +
  log.warn → same function-type work item; the warn may spam logs.
- `:921` — stub classes wish for function-as-return-type → same item.
- `:1288` — "make index of package members" → performance item; note the
  file-based indexes now exist as infrastructure to do it.
- `:1425` — cache import.hx using-statement walking per file → CachedValue
  on the file model; easy win.
- `:1447` — "expand typedefs" when matching using-members → correctness gap.
- `:1479` — module member doc backtracking workaround → BNF/token layout
  item; keep.

## Annotators / ide — 5

- `HaxeAccessAnnotator.java:296` — better message for public-but-denied
  properties → message polish; bundle key addition.
- `HaxeFieldAnnotator.java:41` — "(incorrectly?) skips `this.property`" →
  write the test; the question mark resolves itself.
- `HaxeFieldAnnotator.java:117` — module-level parent support in field
  redefinition check → real since 4.2 module fields exist.
- `HaxeFieldAnnotator.java:199` — "Bug here. (set,get) marked as errors" →
  per Haxe property grammar `(set, get)` IS invalid, so current behaviour
  looks correct; recommend deleting the TODO (author confirm).
- `HaxeClassStubSerializer.java:39` — "filter types?" on class-name sink →
  decide which types belong in the class-name index; likely fine as-is.

## Config / haxelib / execution / codeInsight — 6

- `HaxeSdkType.java:141` — "Test on a mac" (v15-era!) → decade-old; test or
  delete.
- `HaxeConsoleFilterProvider.java:91` — "check if this slows down things" →
  measure root scan on large projects; bounded, likely fine; rewrite as
  behaviour comment.
- `HaxeDefineDetectionManager.java:95` — move project defines to module
  level → superseded by v2 per-container defines; likely DELETE with the V1
  removal (task #31).
- `HaxeDefineDetectionManager.java:147` — detect hl_ver/neko → still valid
  wish.
- `HaxeDefineDetectionManager.java:166` — CUSTOM target params unparsed →
  gap; v2 path may supersede.
- `HaxeDefineDetectionManager.java:200` — rewrite for `<section>` support →
  v2 LimeProjectParser already evaluates sections; this V1 path is likely
  removed by task #31 → fold into V1 removal.

## Model / util — 2

- `HaxeFileModel.java:444` — FIXME move addImport helper into model → small
  refactor, valid.
- `HaxeNamedSubComponentUtil.java:397` — "recursion guard?" on constraint
  members → yes: self-referencing constraints (`T:Comparable<T>`) could
  loop; add guard + test.

## Generation / debugger-ide — 3

- `BaseHaxeGenerateAction.java:49` — "getData for PSI_FILE does not seem to
  work anymore?" → investigate platform change; current fallback works;
  rewrite as behaviour comment once confirmed.
- `LegacyHxcppDebugProcess.java:200` — runToPosition unsupported without
  breakpoint juggling → honest protocol limitation; keep.
- `icons/HaxeIcons.java:40` — Anonymous reuses abstract.svg → asset wish;
  keep.

## v2 / display (branch-era, already tracked) — 4

- `HaxeLimeProjectInfoService.java:262` — mac .app bundle launch.
- `HaxeCompilerDisplayService.java:202` — lime args cache misses include.xml
  edits.
- `HaxeCompilerResolveService.java:163` — no automatic blueprint
  invalidation.
- `HaxeUsageSearch.java:40` — processReferences enumeration for Find Usages.
All four are deliberate deferrals recorded this development cycle; no action.

## Tests — 6

- `HaxeGoToImplementationTest.java:44` — "listen updater task?" → flaky-risk
  note; fine.
- `HaxeEnterActionTest.java:157` + `:177` — formatter indentation
  expectations documented as wrong → the expected-output encodes a known
  formatter bug; convert to real formatter issues later.
- `HaxeCompilerErrorParsingTest.java:34` — disabled windows-path test
  (method rename `disabledtest...`) → port to path-neutral form or delete.
- `ReferenceCompletionTest.java:83` — verify duplicate enum values in
  completion → enable the stricter assertion.
- `ReferenceCompletionTest.java:287` + `:391` — disabled tests (generic
  params from methods; extension methods "temporarily disabled") →
  re-enable and see; resolver has changed massively since.

## Vendored (`hxcpp-debugger-protocol-legacy`) — 3

`Runtime.java:75/370/512` — upstream Haxe-generated runtime notes.
[vendored] — no action ever; excluded from counts of actionable TODOs.
