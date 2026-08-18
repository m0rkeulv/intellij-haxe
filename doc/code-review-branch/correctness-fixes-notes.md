# Correctness fixes — decision notes

- Row 1: `HaxeEditDefineAction` had the same `putDefine` + `panel.refreshTree()`
  compensation as `HaxeAddDefineAction` (only the latter is named in the
  finding); dropped the manual refresh in both, since the tool window panel
  subscribes to `HaxeBuildSettingsListener.TOPIC` and rebuilds on
  `notifyChanged()`.
- Row 2: implemented via a typed `DisplayJson.MalformedPayloadException` so
  the client can reclassify only the parse-failure case (the JSON-RPC
  error-member exception propagates unchanged). `failureDetail` now uses the
  payload as the compiler message when the error flag is set and no log lines
  arrived — the pinned plain-compiler-error shape carries the message only in
  the payload. New `ErrorClassificationTest` pins both classifications.
- Row 3: no existing isReferenceTo/rename test covers enum extracted values
  (test sources only exercise extractors through parser and inlay tests), so
  the `HaxeEnumExtractedValueReference` fix is verified by compile + the
  scoped inference suites only.
- Row 5: `bindingCache` moved into a new project-level
  `HaxeUntypedParameterBindingCache` (plugin.xml `projectService`, cleared by
  `HaxeExpressionEvaluatorCacheChangeListener` and `LowMemoryWatcher`,
  released on project dispose) rather than into one of the existing cache
  services, keeping the untyped-parameter memo semantics documented in one
  place. `settleBinding` first-settle `informationSettled()` and the
  thread-local probe budget are unchanged.

## Question investigations
- Q1 (HaxeBuildConfigListener.TOPIC delivery thread): contract verified +
  documented. Both subscribers are any-thread safe — HaxeToolWindowPanel
  .refreshTree dispatches through `ReadAction.nonBlocking` + `invokeLater`,
  HaxeServerConsoleWindowFactory wraps its handler in `invokeLater` — so the
  topic javadoc now states delivery on the publisher's thread and the
  thread-safety requirement; `HaxeContextHealth.record` stays as-is.
- Q2 (HaxeDefineContextService null→non-null swallow): real bug fixed. No
  other channel reparses on activation (`setActiveFile` → `notifyChanged` →
  `HaxeBuildSettingsListener.TOPIC` reaches only the tool window tree refresh
  and this service). Dropping `before != null` outright would reparse on every
  project OPEN (store `loadState` publishes while `lastComputed` is still
  unset), so a `NEVER_HANDED_OUT` sentinel now distinguishes "no consumer ever
  asked" (no reparse owed) from "consumers were handed null, i.e. parsed under
  the legacy context" (activation reparses).
- Q3 (isReferenceTo fast path vs checkSwitchOnEnum): cannot diverge — verified
  mechanically and by test. A bare `case x:` parses as a plain reference under
  HaxeSwitchCaseExpr (grammar: switchCaseCapture/CaptureVar/Extractor all need
  `=`/`var`/`=>`); both pipelines run the identical tree-walk family before any
  enum check (HaxeResolver runs checkSwitchOnEnum AFTER checkCaptureVar, lines
  264-265), the checks the fast path skips resolve only to non-local elements,
  and checkCaptureVar needs an enclosing `=>` match expression so it never
  fires for a bare case identifier. Guarantee documented at the check site;
  new `HaxeIsReferenceToSwitchCaseTest` (2 tests, green) pins agreement with
  full resolve for the enum-collision and local-shadowing scenarios.
- Q5 (PsiDirectory CachedValue dependencies): real defect (misleading deps +
  javadoc) fixed. Platform sources (`PsiCachedValue.getTimeStamp`, 2026.2):
  a PsiDirectory dependency timestamps as the GLOBAL PSI modification count,
  so the caches already invalidated on any PSI change and the per-file deps
  were dead weight. Both caches now depend explicitly on
  `PsiModificationTracker.MODIFICATION_COUNT` and the javadocs state the real
  granularity (which also covers the added-file case).
- Q6 (findFileByIndex empty-result miss): contract verified + documented.
  Every root the models serve is an order-entry root (HaxeProjectModel
  RootsCache: OrderEnumerator source/classes roots, SDK source roots for std)
  — all indexed under allScope. Unbuilt indexes are dumb mode, already routed
  to the directory walk via IndexNotReadyException; user-excluded subtrees are
  outside platform resolve by design.

## Question dispositions
- Q7 (LegacyHxcppDebugProcess unsynchronized breakpointIds/Value fields): accepted
  as-is — legacy support is slated for removal; revisit only if it causes real
  issues.
- Q8 (pluginSinceBuild 261→262): intentional — 2026.1 support was dropped for a
  262-only API. CLAUDE.md's documented range updated to match.
