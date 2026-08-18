# Chunk 1 — main/model + main/lang (34 files)

## Likely BUGS (highest priority for the fix pass)

- **[discuss] `lang/psi/indexes/filebased/indexer/HaxeFullyQualifiedNameIndexer.java:28-46`
  — the indexer builds and then DISCARDS its result.** `map()` fills
  `indexDataMap` (classes, class members, module members) and then falls
  through to `return Map.of();` — the populated map is never returned. Unless
  something else populates this index, the FQN file-based index is empty and
  every `HaxeFullyQualifiedClassNameUnifiedIndex` file-side lookup silently
  misses (stub-side results mask it for stubbed files). Needs a test proving
  the index contains entries for a non-stubbed file, then `return indexDataMap;`.
- **[discuss] `HaxeModuleFieldNameFileIndex.getIndexer()` returns
  `HaxeClassFieldNameIndexer`** — the CLASS-field indexer feeds the
  MODULE-field index (sibling `HaxeModuleMethodNameFileIndex` correctly uses
  `HaxeModuleMethodNameIndexer`). Copy-paste slip: module-level fields are
  indexed with class-field keys/semantics. Verify + fix with a test for
  module-level field lookup.
- **[fix] Latent NPEs in the index lookup family**: `HaxeClassFieldNameFileIndex`,
  `HaxeClassMethodNameFileIndex`, `HaxeStaticFieldNameFileIndex` call
  `model.getClass(className)` and dereference without a null check
  (`aClass.getField/getMethodSelf`) — `HaxeConstructorFileIndex` DOES check
  `aClass != null`, proving the case is real. Also all of them call
  `getFileData(...).get(name)` and dereference `data.getFqn()` BEFORE the
  `instanceof HaxeFile` guard; a stale index entry returns null data → NPE.
- **[fix] `HaxeClassNameUnifiedIndex.getAllKeys`** — `result.addAll(stubKeys)`
  twice (once via constructor, once explicitly); harmless duplicate but sloppy.
- **[discuss] `model/FullyQualifiedInfo`** — the `List<String>` constructor's
  null-identifier branch assigns `packageName = null` although the field is
  annotated `@NotNull`; every consumer trusting the annotation is one null
  identifier away from an NPE. Either drop the branch (throw) or fix the
  annotation.

## Duplication → extraction candidates

- **The seven `*FileIndex` extension classes are one template**: identical
  INDEX/version/indexer boilerplate plus a copy-pasted
  `getFilesWithKey`-walk lookup whose only variance is the member extraction
  (2-4 lines each). Extract a protected lookup helper on
  `HaxeComponentBaseIndex` taking a `(HaxeModuleModel, name) -> member`
  function; each subclass shrinks to the indexer choice + one lambda. This
  also fixes the NPE inconsistency in ONE place.
- **Target-package knowledge duplicated**: `LookupUtil.TARGET_ROOT_PACKAGES`
  (Set of 11) vs the identical 11-case switch in
  `HaxeIndexUtil.belongToPlatformNotTargeted` — the switch is
  `definitions.containsKey(scope)` per target, i.e. exactly
  `TARGET_ROOT_PACKAGES.contains(scope) -> containsKey(scope)`. One constant,
  one membership check.
- **`FullyQualifiedInfo.toString()` re-implements `getQualifiedName(true)`**
  with a subtle difference: its className branch appends the separator
  unconditionally (leading-dot output when package+module are empty), where
  getQualifiedName guards. Delegate toString to getQualifiedName and the
  divergence disappears.

## Style / structure (checklist: structural style, comments, naming)

- **Anonymous `new Processor<VirtualFile>() {...}` in 6 places** (all index
  lookups + `HaxeImportHxFileIndex`) — `Processor` is functional; lambdas.
  Same file: anonymous `DataIndexer` in `HaxeImportHxFileIndex.getIndexer()`
  wants to be a named nested class like every sibling index has.
- **[fix] `HaxeIndexUtil.BASE_INDEX_VERSION` is `public static int`** — not
  final; anything could reassign the version at runtime. Make it final.
- **[discuss] `HaxeIndexUtil` static-init `log.setLevel(LogLevel.WARNING)`**
  — permanently suppresses info/debug for this logger regardless of user log
  configuration; intended? At minimum a comment saying why.
- **[fix] `HaxeExpressionEvaluatorCacheService`**: `volatile` on a field that
  is never reassigned (ConcurrentHashMap) is dead ceremony; `containsKey` +
  `get` two-step should be single `get` (racy double lookup);
  `synchronized(this)` around `clear()` guards nothing the map doesn't
  already. `skipCaching` debug flag is fine but is a public mutable static —
  comment says so, OK.
- **[fix] `HaxeSyntheticPsiUtil.createSyntheticForTargetSpecificSyntax`** —
  `qname` parameter unused.
- **[fix] `LookupUtil`** — two EMPTY javadoc blocks (`/** */`); either write
  the constraint or delete the block. `isActiveTarget(HaxeComponentIndexData,
  Project)` — verify callers; may be unused after the unified-index work.
- `HaxeResolverScopeProcessor.execute` — the instanceof cascade is a
  pattern-switch candidate (same shape the checklist flags); fine to defer to
  the fix pass. Content itself is sound; the two TODOs are inventoried.
- `HaxeConstructorFileIndex.getConstructors` walks getAllKeys × getFilesWithKey
  — O(all keys) full-index scan per call; caller is completion
  (`HaxeConstructorUnifiedIndex.getCompletionData` uses getAllValues, the
  ctor-walk serves `getConstructors`) — [discuss] perf if it sits on a hot
  path; verify callers before optimizing.
- `HaxeConstructorUnifiedIndex.getCompletionData` — `() -> { return ...; }`
  block lambdas → expression lambdas; otherwise clean.

## Clean bills of health

- `HaxeExpressionCodeFragmentImpl` — exemplary; the platform-trap comments
  (element-type registry leak, DummyHolder reparse, smart-pointer context)
  are exactly what CLAUDE.md asks comments to be.
- `HaxeImportHxFileIndex` — sound; minor: comment typo "statment",
  anonymous indexer noted above.
- `HaxeCodeFragmentUtil`, `HaxeExpressionCodeFragment`, `HaxeStubVersions`,
  `HaxeInheritanceIndexUtil`, both inheritance indexers,
  `HaxeInheritanceDefinitionsSearcher`, `HaxeClassStubSerializer` — reviewed
  (partly rebuilt) earlier this same session; conform.
- `HaxeProjectModel`, `ResultHolder` — reviewed during the branch work this
  session; conform.

## Files still pending in this chunk (continued in this file, part 2)

HaxeFileModel, HaxeCallExpressionUtil, HaxeReferenceSuggestionUtil,
HaxeResolver, HaxeExpressionEvaluatorHandlers, HaxeReferenceImpl.

# Part 2 — the mediums and the big three

## HaxeFileModel.java (446)

- **[fix] Dead experiment left in `getClassModels()`**: hardcoded
  `boolean COLLECT_USING_STREAMS = false;` guarding a never-taken stream
  branch. Delete the flag and the dead branch (`getClassModelsStream` keeps
  its one external caller and stays).
- **[fix] `getQualifiedInfo()` and `getFullyQualifiedInfo()` are identical**
  — same body, both public. One should go (callers updated) or delegate.
- **[fix] `getChildren()` → `getChildren(file)` → `getChildrenCached(file)`**
  — two wrapper hops that add nothing; collapse to the cached call.
- addImport FIXME already inventoried. Otherwise sound; stub-aware paths and
  the code-fragment context/import merging read well.

## HaxeCallExpressionUtil.java (456)

- **[fix] Dead leftover**: in `createContextForConstructorCall(newExpression,
  methodModel, assignHint)` — `PsiElement resolve = ...resolve(); if (resolve
  instanceof HaxeConstructor constructor) { }` — empty if-body with unused
  pattern variable; a stranded experiment.
- **[fix] Four `createContextForConstructorCall` overloads, two of which
  duplicate the same models→contexts→container loop** — one core method +
  thin delegating wrappers.
- **[fix] Ordering trap in `createContextForFunctionCall`**: `tryGetCallieType(
  ..., evaluation.isStaticExtension)` reads the field BEFORE the line that
  assigns it (both happen to be false today — fragile, not wrong).
- **[fix] Naming**: local `MethodsClassResolver` (uppercase like a class) in
  two methods.

## HaxeReferenceSuggestionUtil.java (525)

Well-decomposed (each suggestion source its own small `add*Suggestions`
method); conforms. TODO at :455 inventoried. No findings beyond that.

## HaxeResolver.java (3235) — structural assessment

- **[discuss] Class size / mixed jobs**: the resolver proper plus an entire
  switch/enum-extractor resolution subsystem (~lines 1383-2050:
  checkEnumExtractor, capture-var/switch-var checks, extractor path
  building, `buildExtractVarPath`, argument-index math) — that subsystem is
  a coherent extraction candidate (`HaxeSwitchExtractorResolver`), would cut
  ~700 lines and give the extractor logic a home + focused tests.
- **[discuss] Giant methods**: `doResolveInner` (~180 lines, the ordered
  check-cascade), `findParentAssignType` (~195), `checkEnumMemberHints`
  (~214). The cascade shape is fine; the three bodies want the named-step
  treatment.
- **[discuss] Debug instrumentation checked in**: `reportCacheMetrics`
  ("Should always be false when checked in") + 3 AtomicInteger counters +
  REPORT_FREQUENCY — permanent debug scaffolding in the hottest class;
  either promote to a proper (registry-flagged) metric or remove.
- Twin-method merge (resolveChain/resolveByClassAndSymbol) + 8 more TODOs
  already inventoried in findings-01.

## HaxeExpressionEvaluatorHandlers.java (2678) — structural assessment

- **[discuss] One static class handles ~40 expression forms**; natural split
  by domain (literals / call expressions / switch-extractors / control flow)
  — the switch-extractor cluster again appears here, mirroring the resolver.
- **[fix] Duplicated extern-ArrayAccess workaround**: the identical
  ~10-line "hack to work around external ArrayAccess interface" block exists
  twice (:1309, :1349) — extract once.
- The debug throw at :133 is chipped; 24 TODOs inventoried; several
  commented-out alternates flagged there ride along.

## HaxeReferenceImpl.java (1479) — structural assessment

- Platform-shaped (implements the wide PsiReference/PsiClass surface);
  size is inherent, cohesion is fine. The three deliberately-unimplemented
  methods log-warn behind a flag — acceptable. 6 TODOs inventoried.
- **[fix]** `getCanonicalText/CandidateInfo` duplication noted in TODO
  inventory; nothing further beyond part-1 items.

Chunk 1 complete: 34/34 files processed.
