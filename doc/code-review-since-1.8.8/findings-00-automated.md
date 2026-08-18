# Phase 0 — automated sweeps (2026-07-31)

Mechanical scans over the 983 in-scope files. Everything here is a FINDING,
not a change — report-only per the assignment.

## TODO inventory (checklist: TODOs)

101 TODO/FIXME markers in scope. Three are naked (say nothing) and need
triage during the owning chunk:

- [discuss] `src/main/java/com/intellij/plugins/haxe/lang/psi/stubs/serializers/HaxeClassStubSerializer.java:113` — bare `//TODO`
- [discuss] `src/main/java/com/intellij/plugins/haxe/model/evaluator/HaxeExpressionEvaluatorHandlers.java:132` — bare `//TODO` above the
  `throw new RuntimeException("MLO: inspect")` debug leftover (already chipped
  as its own task earlier today)
- [discuss] `src/main/java/com/intellij/plugins/haxe/model/evaluator/HaxeExpressionEvaluatorHandlers.java:297` — bare `//TODO`

The remaining 98 get per-chunk triage (solved / solve now / legit deferral)
when their area is processed; full list preserved from the sweep run.

## Orphan classes (checklist: dead code)

Scan: classes whose simple name has zero references outside their own file.
283 raw hits, 113 after excluding test classes (framework-invoked, not
orphans). The 113 fall into distinct buckets — most are NOT dead:

- **DAP/JsonRpc protocol DTOs** (~75: `dap-protocol` requests/responses/events,
  vshaxe `jsonrpc/*`, `HxcppProtocol` info types): POLICY SETTLED by the
  maintainer (2026-07-31): the protocol surface is deliberately
  spec-complete; spec messages are NEVER trim candidates — only custom
  additions we made ourselves may be questioned. Follow-up [fix] for the fix
  pass: state this intent in the dap-protocol README, and during the
  debugger chunks flag any DTO that is a custom addition rather than spec.
- **Vendored Haxe->Java runtime** (~30: `hxcpp-debugger-protocol-legacy/src/fallback/...`):
  generated/vendored support classes. [vendored] — no action.
- **compat-matrix mains** (5: `MatrixMain`, `Provisioner`, `GradleRunner`,
  `TestEventTail`, `VersionManifest`): entry points invoked by gradle tasks,
  not by java references. Not dead; mark NA after confirming the gradle
  wiring names them.
- **Real orphans — DEFINITIVE (re-verified 2026-08-01 with an unrestricted
  search over code, resources, plugin.xml and build scripts)**. Exactly FIVE
  classes have zero references anywhere; all five pre-date the review
  baseline branch work:
  1. ~~`src/main/java/com/intellij/plugins/haxe/runner/debugger/dap/ide/DapSmartStepIntoHandler.java`~~
     RESOLVED (deleted 2026-08-01): diff against PsiResolvedSmartStepHandler
     showed an identical class up to the rename and javadoc — the stale
     original left behind when the PSI-resolved/adapter-targets split was
     introduced.
  2. ~~`src/main/java/com/intellij/plugins/haxe/runner/debugger/hashlink/HashLinkSmartStepIntoHandler.java`~~
     RESOLVED (deleted 2026-08-01): superseded by
     AdapterTargetsSmartStepHandler, which is the same class generalized for
     both stepInTargets adapters (adds js-debug label parsing +
     known-limitation doc); HashLinkBackend correctly wires the shared one.
  3. ~~`src/main/java/com/intellij/plugins/haxe/buildsystem/hxml/lexer/HXMLLexerAdapter.java`~~
     RESOLVED (wired in 2026-08-01): HXMLParserDefinition.createLexer now
     returns it, hiding the FlexAdapter/Reader-null construction. One inline
     construction remains in HXMLSyntaxHighlighter.getHighlightingLexer.
  4. ~~`src/main/java/com/intellij/plugins/haxe/util/PsiElementDelegate.java`~~
     RESOLVED (deleted 2026-08-01): no current use; maintainer's call.
  5. ~~`src/main/java/com/intellij/plugins/haxe/util/StreamUtil.java`~~
     RESOLVED (deleted 2026-08-01): single reverse(Stream) helper, zero
     callers; a future need is covered by toList().reversed().stream()
     (SequencedCollection, Java 21+).
  ALL FIVE RESOLVED — the orphan-class finding is closed.
  Every OTHER non-test suspect from the first scan (`DapJson`,
  `DapFraming`, `EvalConnection`, `FixtureSession`, `FakeHxcppServer`,
  `LiveProbeUtil`, the compat-matrix mains, the JsonRpc/Hxcpp types) HAS
  references — the first scan's debugger-module pathspec failed to match,
  producing false zeros. They are alive; no action.

## Registrations (checklist: registrations & references)

plugin.xml re-verified this session: all class references resolve except the
pre-existing `TypeCheckExpressionChecker$IncompatibleTypeChecks` (has its own
task chip).

## Platform API (checklist: platform API)

Root-module recompile with warnings shows two pre-existing deprecation sites:
- [fix] `src/main/java/com/intellij/plugins/haxe/ide/generation/OverrideImplementMethodFix.java:149` — calls removal-marked
  `HaxePresentableUtil.getPresentableParameterList(...)` (our own API).
- [vendored] `hxcpp-debugger-protocol-legacy` fallback `Type.java` uses
  deprecated JDK API.

Debugger submodules compile without deprecation notes (checked via the full
build earlier today).

## Deprecation sweep (-Xlint:deprecation, all modules, run 2026-08-01)

Every module recompiled with `-Xlint:deprecation` via an init script (no
build-script changes). 74 unique warning sites across 28 deprecated APIs;
full per-site list in `findings-09-deprecation-sites.txt`. Notable:

- [no action] `RunInEdt` (testFramework.junit5) is deprecated — 20 sites, all
  ours BY DESIGN (test bases + the @Nested rule in CLAUDE.md mandates it).
  Investigated 2026-08-01: there is NO usable replacement for Java tests.
  The ReplaceWith(RunMethodInEdt) hint is misleading — RunMethodInEdt is a
  per-method MODIFIER that only works when the deprecated class-level
  RunInEdt registers the interceptor (EdtInterceptorExtension is registered
  solely via @ExtendWith on RunInEdt; shouldIntercept even calls
  Optional.get() on the class annotation). The real successor is the
  Kotlin-coroutine idiom timeoutRunBlocking(Dispatchers.UiWithModelAccess),
  i.e. a wholesale Kotlin rewrite. Test-only API, no marketplace exposure:
  keep RunInEdt; revisit if a Java-friendly successor ships.
- [fix] `ReadAction.compute` — 2 sites the earlier branch-wide migration to
  `computeBlocking` missed: `ide/quickfix/typedialog/HaxeCreateTypeDialogBuilder.java:187`
  and `ide/HaxeFindUsagesHandlerNS.java:192`.
- [discuss] `DaemonCodeAnalyzer.restart()` — 5 sites, all in v2 display
  services; one shared restart helper would fix all at once.
- [discuss] our OWN deprecated APIs still called: `HaxeBaseMemberModel.getResultType()`
  (9 sites), `HaxeResolveUtil.getHaxeClassResolveResult` (2 sites) —
  either finish those migrations or un-deprecate.
- Full per-API triage (replacements read from the 2026.2 javadocs,
  2026-08-01). Drop-in one-liners, [fix]:
  - `DaemonCodeAnalyzer.restart()` → `restart(Object reason)` — pass a log
    reason string (5 sites: v2 display services + HaxeLanguageLevelUtil).
  - `Thread.getId()` → `threadId()` (HaxeDebugUtil:217, HaxeResolveUtil:448).
  - `InputEvent.ALT_MASK` → `ALT_DOWN_MASK` (HaxeIntroduceDialog:77).
  - `JList.getSelectedValues()` → `getSelectedValuesList()`
    (HaxeRestoreReferencesDialog:74).
  - `StubBasedPsiElement.getElementType()` → `getIElementType()`
    (HaxePsiTypeAdapter:289,290).
  - `DocumentationManagerUtil.createHyperlink(5-arg)` → 4-arg overload
    (HaxeDocumentationProvider:206,284,410).
  - commons-lang `StringUtils.startsWith`/`replace` → plain JDK String
    methods, mind null args (HaxeFindUsagesHandlerNS:152,154,
    HaxeFileModel:428).
  - `TextAttributesKey.createTextAttributesKey(String, TextAttributes)` →
    the `(String, TextAttributesKey fallback)` overload with a
    DefaultLanguageHighlighterColors fallback (HXMLSyntaxHighlighter:45,
    HaxeSyntaxHighlighterColors:104).
  - `OrderEntry/LibraryOrSdkOrderEntry.getFiles` → `getRootFiles(OrderRootType)`
    (PsiFileUtils:71, HaxeToolWindowNavigation:119).
  - `RegExpLanguageHost.supportsPossessiveQuantifiers()` → the
    `(RegExpElement)` overload (HaxeRegularExpressionImpl:101).
  - `CodeStyleSettingsManager.getSettings(Project)` →
    `CodeStyle.getSettings(PsiFile)` (HaxeMoveDeclarationHandler:119; also
    tests, where `CodeStyleSettings.clone()` →
    `CodeStyle.runWithLocalSettings`).
  Real work, [discuss]:
  - `BeforeRunTask.read/writeExternal` → PersistentStateComponent pattern,
    example LaunchBrowserBeforeRunTask (HaxeActionBeforeRunTaskProvider —
    OUR v2 code, worth doing properly).
  - `XDebuggerManager.startSession` + `XDebugSession.getRunContentDescriptor`
    → `newSessionBuilder()…startSession()` — but both sites are V1 legacy
    (LegacyHxcppDebugRunner, HaxeFlashDebuggingUtil) likely deleted by the
    V1 removal; fix only if they survive.
  - `SdkType.suggestHomePath()` is abstract-deprecated: the override in
    HaxeSdkType:67 is FORCED (warning unavoidable without suppression);
    additionally override `suggestHomePath(Path)` per javadoc.
  - `StartupManager.runWhenProjectIsInitialized` → `ProjectActivity` EP
    (HaxelibProjectUpdater:1590 — legacy haxelib area).
  - `CodeStyleSettingsProvider.createSettingsPage` → `createConfigurable`
    (HaxeCodeStyleSettingsProvider:41).
  - `PsiUtil.isLanguageLevel6OrHigher` → javadoc says inline or
    `PsiUtil.isAvailable(JavaFeature.OVERRIDE_INTERFACE, element)`; the
    HaxePullUpHelper:276 guard tests the JAVA language level of a Haxe
    class, which is meaningless — copied from the Java pull-up helper;
    the whole branch probably wants inlining to just `myIsTargetInterface`.
  - `Class.newInstance` in the vendored fallback Type.java: [vendored].

## Bundle-violation sweep (heuristic greps, run 2026-08-01)

String literals fed to user-visible surfaces in main sources. Notifications,
dialogs, validation (`createNotification`/`Messages.show*`/`ValidationInfo`/
`setErrorText`/`setTitle`), labels and `registerProblem` are CLEAN — one
`createNotification("")` in HaxelibNotifier is a placeholder immediately
overwritten with bundle messages, not a violation. The real findings:

- [fix] the semantic-annotator cluster: ~40 hardcoded English messages in
  `holder.newAnnotation(...)` across 12 files —
  HaxeUnaryExpressionAnnotator (9), HaxeMethodAnnotator (12),
  HaxeFieldAnnotator (7), HaxeClassAnnotator (2), HaxePackageAnnotator (2),
  HaxeNullCoalescingAnnotator (2), HaxeTypeAnnotator, HaxeReturnStatementAnnotator,
  HaxeMetadataAnnotator, HaxeAccessAnnotator, HaxeFastColorAnnotator,
  HaxeHxmlFastColorAnnotator (1 each). These are editor error/warning texts —
  squarely bundle material (HaxeBundle).
- [fix] `ide/refactoring/memberPushDown/PushDownProcessor.java:197` —
  `Messages.showMessageDialog("No subclass to push down to ", ...)` (note the
  trailing space).

## Structural greps

Folded into each manual chunk (findings-02..08) rather than one global
pass, so those findings sit next to their area reports.
