# Chunk 2 — main: ide / util / haxelib / config / root (43 files)

## Likely bugs

- **[discuss] `HaxeUnresolvedSymbolInspection.handleUnresolvedReference`** —
  the import-statement branch adds an ERROR descriptor and then FALLS THROUGH
  to also add the generic LIKE_UNKNOWN_SYMBOL descriptor for the same
  identifier: unresolved imports appear to get double-reported. Verify with a
  fixture; if confirmed, `return` after the import branch.
- **[discuss] `HaxeConsoleFilterProvider`** — when the compiler message uses
  "lines" the column is forced to "0", then `columnNo - 1` = -1 is handed to
  `OpenFileHyperlinkInfo`; verify the platform tolerates -1 or clamp to 0
  (the stacktrace branch clamps with Math.max, this branch does not).
- **[discuss] `HaxeMetaTagsCompletionContributor`** — `ModuleUtil
  .findModuleForFile` result is passed to `HaxeCompletionCache.getInstance
  (module)` without a null check (scratch/library files have no module).
- **[discuss] `HaxeDefineDetectionManager.moduleDefinitionsMap` is a PUBLIC
  STATIC mutable map inside a project service** — application-global state
  keyed by Module: retains modules of closed projects unless every removal
  path fires (leak risk), is writable by anyone, and belongs as instance
  state of the service.

## Dead code / dead settings

- **[discuss] Compiler-completion SDK settings may be dead UI**:
  `HaxeAdditionalConfigurablePanel` still offers "use compiler completion" +
  "remove completion duplicates" checkboxes persisted into HaxeSdkData — but
  `HaxeCompilerCompletionContributor` was DELETED on this branch. If nothing
  reads `getUseCompilerCompletionFlag`/`getRemoveCompletionDuplicatesFlag`
  outside the dialog round-trip, the whole settings row + fields are dead.
- **[fix] `HaxeSyntheticMemberCompletionContributor.addVariantsFromIndex`** —
  computes `scope`/`matcher` locals and takes a `filterText` parameter, none
  used; `addTrace` computes an unused `identifier`; and the method name lies
  (it adds synthetics, not index variants) — name-drift rule.
- **[fix] `HaxeIndexedClassLookupElement`** — `strikeout`/`bold` are
  hardcoded-false final fields dressed as state; constructor local
  `qualifiedInfo` shadows the field; field is non-final for no reason.

## Duplication → extraction candidates

- **The four unused-* inspections are one template** (visit → collect →
  descriptors, differing only in the visited element and fixes). An abstract
  base kills ~200 duplicated lines and unifies the search-scope choice
  (field inspection searches project-wide; local var/function use smallest
  scope — the asymmetry is intentional but currently invisible).
- **The two add-import intentions are near-clones**
  (`HaxeTypeAddImportIntentionAction` / `HaxeStaticMemberAddImportIntention
  Action`): same HintAction/QuestionAction/LocalQuickFix scaffold, same
  invoke/popup/preview flow; only candidate type + qname derivation differ.
  Abstract base with two small subclasses.
- **`HaxeAddImportHelper`**: addImport/addUsing and the four insert* methods
  are pairwise identical modulo element type; also the insert*Before methods
  actually addAfter their anchor — misleading names.
- **`HaxeClassNameCompletionContributor`**: two of the four anonymous
  CompletionProviders have byte-identical bodies (simple identifier + smart
  in-function-type-tag) — share one provider instance.
- **`HaxeAccessAnnotator.hasAccessMetaFor` / `hasAllowMetaFor`** — twin
  ~50-line walks differing in the metadata type consulted; unify with the
  metadata-type as parameter.
- **`HaxeAdditionalConfigurablePanel`** — three copy-paste browse
  ActionListeners; also hand-rolls what
  `TextFieldWithBrowseButton.addBrowseFolderListener` provides.

## Style / structure

- **[fix] Bundle violation**: `HaxeUnresolvedSymbolInspection` hardcodes
  "Module must start by upper case" (also ungrammatical) — bundle key +
  wording fix. Also the add-import intentions build user-visible text as
  `qname + " ?"` inline — bundle key.
- **[fix] Method-name typos**: `handleInncorrectModuleName` (Unresolved
  inspection), `proccessHxmlModule`/`proccessNmmlModule` (define manager),
  `createSynteticMember` (synthetic completion).
- **[fix] Debug leftovers**: `HaxelibModuleManagerService` static-init
  `log.setLevel(LogLevel.INFO)` with the comment "Take this out when
  finished debugging" — and `HaxeDefineDetectionManager` has a similar
  static block. Same pattern as `HaxeIndexUtil` in chunk 1: decide a policy
  (no per-class level overrides checked in).
- **[fix] `HaxelibInstalledIndex`** — `public static HaxelibInstalledIndex
  EMPTY` non-final; legacy `Hashtable` (×2) instead of Map; raw-typed
  `new Hashtable(...)` copy; parameters named `Library` (uppercase).
- **[fix] `HaxeSdkType`** — verify `ProjectJdkImpl` in `ensureSdk` against
  the internal-API rule (impl-package class; check the annotation in 2026.2).
- **[fix] `HaxeAdditionalConfigurable`** — anonymous `new Runnable(){}` →
  lambda; Hungarian locals (`bUseCompilerCompletion`).
- **[fix] `CreateClassAction`** — `private static Set<...> SOURCES` non-final
  + static-init block for a two-element constant → inline `static final`.
- **[fix] `BaseHaxeGenerateAction.actionPerformed`** — missing @Override;
  `assert project != null` as production guard → early return.
- **[fix] `HaxeConsoleFilterProvider`** — the two Pattern fields are
  package-private instance fields; should be `private static final`
  (compiled once, no instance state). Regex comments present ✓.
- **[fix] `HaxeResolveUtil`** — `resolveStack` ThreadLocal: anonymous
  initializer → `ThreadLocal.withInitial`, and make the field final.
- **[fix] `HaxeNamedSubComponentUtil._getNamedSubComponentsInType`** —
  underscore-prefix naming.
- `HaxeProjectSdkSetupValidator` — extends the JAVA validator class
  [discuss: platform-coupling choice]; getDistinctRoots/Stream wrapper pair
  collapses; old-style switch → arrow.
- `HaxeDebuggerBundle` — hand-rolled soft-reference bundle cache instead of
  the modern DynamicBundle INSTANCE idiom every other bundle here should
  share; align all bundle classes in one pass.

## Structural assessments (big files)

- **`HaxeResolveUtil` (1657)** — grab-bag util doing four jobs: qname
  splitting/find-by-qname, class-resolve-results (the ~180-line
  `getHaxeClassResolveResultInternal`), import/using/same-package search
  (lines ~1135-1520, a coherent `HaxeImportSearchUtil` extraction), and the
  qname-derivation used by indexes. The 12 TODOs inventoried previously
  cluster on exactly these seams — the file is the single best refactor
  target after HaxeResolver itself.
- **`HaxeAccessAnnotator` (586)** — well-decomposed; only the twin-walk
  dedupe above.
- **`UsefulPsiTreeUtil` (434)** — healthy generic tree helpers; mixed
  `static public`/`public static` orderings, otherwise fine.
- **`HaxeNamedSubComponentUtil` (421)** — sound; recursion guards in place.
- **`HaxeDefineDetectionManager` (250)** — V1-coupled paths (HaxeModule
  Settings) expected to fall with task #31; static-state finding above is
  the part worth fixing regardless.

## Conforming (largely session-reviewed)

HaxeStandardAnnotation, HaxeLanguageFeatureAnnotator, the five gated
annotators, HaxeFieldAnnotator, HaxeUnusedMethodInspection, HaxePluginPaths,
HaxeHelpUtil, HxpFileType, HaxeModuleDetection, HaxeProblemFileHighlightFilter,
HaxeModuleType, HaxeDebuggerBundle (modulo the idiom note), HaxeIcons,
HaxelibProjectStartActivity, HaxeMetaTagsCompletionContributor (modulo the
null-module check), CreateClassAction (modulo the constant).

Chunk 2 complete: 43/43.
