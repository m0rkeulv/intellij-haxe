# Branch review ledger — dev/project-structure-V2-merged...HEAD

Generated 2026-08-16 from the 14 per-chunk findings files in `findings/`.
251 changed files since the 2026-08-12 fork (27 commits: test-runner phases,
flash/AIR/node/browser debug lanes, Haxelib Explorer, trusted-project gating,
SDK runtimes UI), reviewed against the CLAUDE.md Code style / Code review /
Test code checklists.

## Fix status (updated 2026-08-17)

Fixed and committed, batch by batch:

- **1cbbeb3c4** — the 3 fix-now items; all duplication findings (per-type
  switches → interface methods, one named home for repeated
  expressions/predicates/constants incl. LimeProjects export layout,
  HaxeSystemPaths cache dirs, HaxelibSemVer pseudo-version constants,
  HxmlFileParser tokenizer + separator predicates, FlexPluginGate,
  reporter sources shared via sharedLiveReporter); both open questions
  (dialog apply backgrounded; munit single-test filter fails the compile
  when unsound).
- **404ccb3ad** — correctness batch: gutter-context structural caching,
  BrowserTestRunHost initialize/deadline/Stop gaps, tool-override rule,
  lime display failure logging, trace-path cache key, HaxelibCacheManager
  concurrency + disposal, trust-guard ordering, AIR temp dirs, presentation
  descriptions, SM-properties disposal, dead constructors, adapter display
  name, container-id reset, OSAgnosticPathUtil for deprecated FileUtil call.
- **eddc81ffc** — bundle texts: orphan filter.group key deleted,
  adl-exit/neko-no-module keys added, key groups re-sorted with blank-line
  separation, dead headers removed, ellipsis/period/dash normalization.
- **comments/javadoc/naming batch** — HxmlFileParser broken link + stale
  accumulator doc + regex comment; HaxeBuildClasspaths javadoc states the
  raw lime `<source>` fact; HaxeKnownBuildFiles read-action contracts;
  HaxeDebugAdditions + BuddyFramework TODO form; HaxeMethodStubFactory
  comment; HaxeSourceRootsApplier KDoc link; explorer filters javadoc (two
  narrowing filters); marker javadocs say marked-or-conventional;
  NodeAttachLiveProbe glob wire fact; test history comments dropped
  (sections test, RunControlIT); DisplayName fixes (hashlink backend scope,
  browser test capture, run-config kind, marker contributor);
  HaxeDebugSupportTest test-prefixed methods + misplaced assertion;
  registerTestsBuildInSubfolder rename; munit/buddy/tink reporter READMEs
  written; browser-debugger README inventory + node/browser test-lane wire
  facts.

- **187912981** — threading batch: HaxelibCacheManager.sdkContext and
  HaxelibExplorerPanel.haxeModule go through the new
  HaxeReadActions.compute (per-thread dispatch: computeBlocking on the
  EDT, executeSynchronously on pooled threads — both sites are reached
  from BOTH contexts; a pooled-only executeSynchronously swap crashed the
  explorer's EDT selection path in sandbox testing);
  HaxeBuildToolsConfigurable and HaxeAdditionalConfigurable compute the
  inherited runtime defaults (PATH scans) off the EDT and push them to the
  panel; HaxeTestDebugRunner computes the launch plan once in validate and
  reuses it in createBackend/createCommandLine (was three blocking read
  actions per launch), and its instanceof chain became a pattern switch
  with one-line arms per the new CLAUDE.md switch-arm rule.

- **test-code batch** — toolAvailable probe bounded (10s waitFor +
  destroyForcibly, expiry reads as unavailable); HaxelibLocalDocsTest
  writes ReadMe.MD so the case-folding claim is actually exercised;
  NmeProjectsTest regex assert named + commented; assert conditions named
  (hl launcher, locator paths, marker predicate, tink assertInstanceOf);
  live-reporting save/restore uses capture-and-restore in both tests;
  member order fixed (constants first in HaxeLibrarySyncTest; helpers
  below tests in HaxeBuildSystemTest, HaxeBuildFileSectionsTest,
  HashLinkBackendScopeTest, HaxeProgramLaunchesTest); events-converter
  expected strings are text blocks; NodeAttachLiveProbe imports TimeUnit.
  Already fixed in earlier passes, verified: detection-test base class,
  probe helper dedup into LiveProbeUtil (incl. CHROMIUM_PATHS),
  pipeline-test member order + runCompile tail, adlAvailable reuse.

- **a06bcd595** — test-code batch (see below).
- **c885b48a6** — style/structure batch: also applies the new CLAUDE.md
  "guarded computation is a small method, not a wrapping ternary" rule
  (explorer docs/devPath/gitCheckout lookups; three var one-liners).
  Details: events converter: named locals for the
  rewrite/hint computation, kind dispatch as a switch, consumer-less
  1-arg injectLocationHint deleted (tests pass null); planner: dead null
  ternary in nmePlan; run configuration: limeDisplayInputs named method
  with the parent null guard, shortClass local, LIME_DISPLAY_TIMEOUT_MS
  with its sibling constants, wildcard buildtools imports (also
  HaxeTestSingleRuns, HaxeBuildSections, explorer panel); configurations:
  compileStepUpdates named method; editor: scanKnownBuildFiles + one
  Collectors pipeline; haxe:test protocol constant moved to
  HaxeTestNameLocation (v2.testing must not depend on run);
  DapSourceResolver locals + byNameUnderRoots; HaxeBuildFileInspector:
  currentSection local, exhaustive type switch, dead isEmpty guard,
  no-PSI fallback documented, stray "verified live" dropped;
  HxmlFileParser flag-set constants clustered; LimeProjects target-set
  constants at the head; NmeProjects jsprime arm folded; unused
  HxmlProjects import; HaxeSourceRootsOffer via MessageDialogBuilder;
  HaxeBuildClasspaths LocalFileSystem local; HaxeRegisterBuildFileAction
  moved to v2.toolwindow.actions (plugin.xml updated); tool window:
  CommonShortcuts.ENTER, click forwarders inlined, TestsGroupNode's dead
  ownerId/count dropped, remove-node toolbar path confirms like the
  Delete key and sets text+description per branch (new
  remove.build.file.description key); HaxeProgramLaunches FLASH arm as a
  block; before-run provider's resolveBuildFile shared head;
  HaxeMethodPsiMixinImpl always-true instanceof reduced; kt controls:
  FileChooserDescriptorFactory.singleFile, unreachable renderer null
  guard dropped; explorer: overview rows as formatted text blocks,
  FilterToggle constructed directly, comparator/render payloads named,
  wildcard haxelib import, versionEntries private, docs pipeline moved
  into HaxelibDocsLoader beside the details pane.

- **9f30e092b** — sweep of the new wrapping-ternary rule over all branch
  files: 11 entangled guard+computation ternaries became small if-guard
  methods (DapDebugProcess ×6, HaxelibCacheManager ×2, before-run
  provider, model builder, js-debug probe); ~40 aligned one-line-branch
  ternaries judged fine and kept.

Still open: air.runner key naming smell (accepted for now:
FlexPluginGate is the single home), deprecated ReadAction.compute uses
in committed files (chip task exists).

Several findings were independently reported by more than one chunk (the
orphan `haxelib.explorer.filter.group` key by 08/11/14; the
`resolveSourceMapLocations` javadoc contradiction and the `TimeUnit` FQN by
04/12; the runner helper duplication by 02/14; the `reporterClasspath`
per-type switch by 01/03/14; the plural-READMEs citation by 03/11/14) — the
totals below count each report, so the distinct-issue count is a little
lower.

## Totals

| Severity | Count |
|---|---|
| fix-now | 3 |
| should-fix | 65 |
| minor | 94 |
| question | 2 |
| **total** | 164 |

## Per chunk

| Chunk | fix-now | should-fix | minor | question |
|---|---|---|---|---|
| [01-testing-frameworks](findings/01-testing-frameworks.md) | 0 | 3 | 5 | 0 |
| [02-testing-run-a](findings/02-testing-run-a.md) | 0 | 4 | 8 | 0 |
| [03-testing-run-b](findings/03-testing-run-b.md) | 1 | 3 | 9 | 1 |
| [04-debugger-dap-browser](findings/04-debugger-dap-browser.md) | 0 | 5 | 6 | 0 |
| [05-debugger-flash-air-neko](findings/05-debugger-flash-air-neko.md) | 0 | 3 | 7 | 0 |
| [06-buildtools-a](findings/06-buildtools-a.md) | 1 | 4 | 6 | 0 |
| [07-buildtools-b](findings/07-buildtools-b.md) | 1 | 5 | 8 | 0 |
| [08-haxelib-explorer](findings/08-haxelib-explorer.md) | 0 | 7 | 7 | 0 |
| [09-toolwindow-runconfig](findings/09-toolwindow-runconfig.md) | 0 | 6 | 11 | 0 |
| [10-sdk-config-misc](findings/10-sdk-config-misc.md) | 0 | 7 | 9 | 0 |
| [11-resources-reporters](findings/11-resources-reporters.md) | 0 | 2 | 4 | 1 |
| [12-tests-a](findings/12-tests-a.md) | 0 | 8 | 5 | 0 |
| [13-tests-b](findings/13-tests-b.md) | 0 | 4 | 5 | 0 |
| [14-cross-cutting](findings/14-cross-cutting.md) | 0 | 4 | 4 | 0 |

## fix-now (full text)

### fix-now — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestRunConfigurations.java:65
`findOrCreate` unconditionally does `configuration.setFilterPattern(filterPattern)` on a configuration it may have just *found*, and the whole-build match key is only the build file path (class/method both empty). Both production callers (`HaxeToolWindowPanel.java:286` and `:290`) pass `null`, which `setFilterPattern` notNullizes to `""` — so pressing Run/Debug on a tests row in the tool window silently erases the "Test filter pattern" the user typed into that very configuration's editor, and the run then executes the whole suite. Fix: apply the pattern only when the caller supplies one, or drop the parameter entirely — no caller ever passes a non-null value.

### fix-now — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeDefineContextService.java:100
`getActiveDefines()` now opens with `HaxeKnownBuildFiles.effectiveActivePath(project)` ABOVE the `fastState` short-circuit whose contract is "no VFS or read-action work — this runs per candidate inside index lookups". When no active file is stored, `effectiveActivePath` falls through to `HaxeKnownBuildFiles.all()`: per-module `HaxeBuildFileScanner.scan` (documented read-action-only, can run a Haxe PSI check) plus `LocalFileSystem.findFileByPath` per manual path — on every call, uncached, outside any read action, on the hot paths feeding `HaxeConditionalExpression`, `HaxeFastColorAnnotator`, `LookupUtil` and `HaxeIndexUtil`. Fix: keep the raw store read on the fast path and resolve the implicit single-file fallback once, cached and invalidated on the build-settings topic.

### fix-now — src/main/java/com/intellij/plugins/haxe/v2/buildtools/NmeProjects.java:98-119
The new `targetFor` method was inserted **inside** `targetArtifact`'s doc comment, splitting it across two declarations: the artifact-layout sentences now sit above `targetFor` (which they do not describe) and the sentence's tail is stranded above `targetArtifact`. Restore `targetArtifact`'s `///` block as one contiguous comment directly above it and place `targetFor` with its own doc outside it.

## should-fix (locations — full text in each chunk file)

- v2/testing: HaxeTestGutterContext.java:46 (whole-project rescan on every PSI change); HaxeTestFramework.java:22 + HaxeTestLaunchPlanner.java:110/112 (reporterClasspath per-type switch → interface method; reported by 01/03/14); MunitFramework.java:55 (+Tink/Buddy reportingArgs duplication, teamcity_suite_name constant)
- v2/testing/run: HaxeTestDebugRunner.java:114 (instanceof chain → pattern switch); HaxeTestDebugRunner.java:203 + HaxeTestFlashDebugRunner.java:103 (buildFileModule/sourceDirectories duplicated, drifted; reported by 02/14); HaxeTestLaunchPlanner.java:166 (reporting-args assembly duplicated), :238 (nme target mapping duplicated vs NmeProjects); HaxeTestRunConfigurations.java:33-39 (dead 3-arg findOrCreate); HaxeTestReporterFiles.java:32-58 (four per-framework accessors); HaxeTestRunConfiguration.java:161-170 (inline payload lambda)
- runner/debugger: HashLinkBackend.java:52 + HxcppIntellijBackend.java:37 (dead short constructors); HaxeFlashDebuggingUtil.java:166 (getAirTestDescriptor near-copy); AirRunConfiguration.java:105 (effectiveFlexSdkName duplicated); NodeTestDebugBackend.java:106 (stdout gobbler re-implemented), :62 (hard-coded adapter display name); NodeAttachLiveProbe.java:49 (javadoc contradicts sent config; also 12); BrowserTestCaptureLiveProbe.java:72 (probe helpers duplicated; also 12); browser-debugger/README.md:139 (stale inventory, missing node wire facts)
- v2/buildtools: HaxeCompileCommands.java:122 (producesSwf re-derives launchTarget, diverged on nme air), :118 (live-verified javadoc); HaxeBuildFileActions.java:26 (defaultBuildActionName duplicated, HXML identity vs bundle); HaxeCompilationServerManager.java:204 (trust guard after teardown, no fireStateChanged); HaxeRegisterBuildFileAction.java:70 (register pipeline duplicated + drifted); LimeProjects.java:180-238 (export layout spelled 3×); HaxeLimeProjectInfoService.java:197 (targetOutputFor parallel layout switch); HaxeBuildToolsConfigurable.java:66 (PATH scans on EDT); HaxeTestsBuildFileStore.java:115 (predicate ×4)
- haxelib/explorer: HaxelibCacheManager.java:74 (forceReload pass-through), :129 (refreshLibraryInfo consumer-less), :174 (computeBlocking on pooled threads), :47 (half thread-safe maps), :216 (dispose nulls module under in-flight sweep); HaxelibExplorerActions.java:180 (confirmRemoval ×2); HaxelibExplorerPanel.java:632 (docs pipeline belongs beside the details pane)
- v2/toolwindow + runconfig + display: HaxeToolWindowPanel.java:1017 (selectionPoint duplicate); HaxeRunUnitTestsAction.java:58 + HaxeDebugUnitTestsAction.java:61 (resolveTestsPath ×2); HaxeRemoveNodeAction.java:38 (no confirmation on toolbar path); HaxeToolWindowModelBuilder.java:409 (connectEligible predicate restated), :123 (tests-path filter differs from HaxeLibrarySync); HaxeCompilerDisplayService.java:157 (section expansion re-implemented)
- buildsystem/sdk/psi: HxmlFileParser.java:20 (broken {@link #sections}), :261 (stale accumulator javadoc), :231-244 (mainClass re-tokenises); HaxeBuildFileInspector.java:96 (--next/--each literals); HaxeRuntimeSettingsControls.kt:45 (pathHit duplicate), :110 (flex predicate ×2); HaxeMethodPsiMixinImpl.java:100 (always-true instanceof)
- resources: HaxeBundle.properties:429 (orphan filter.group key; reported by 08/11/14); testing reporters FlashSupport/escape/printLine duplicated ×3-4 (11)
- tests: HaxeCodeInsightFixtureTestCase.java:317 (unbounded waitFor); NmeProjectsTest.java:63 (computed assert + uncommented regex); HaxelibLocalDocsTest.java:93 (toLowerCase no-op cancels case coverage); HaxeBuildFileSectionsTest.java:109 (history comments); RunControlIT.java:129 (changelog javadoc); NodeAttachLiveProbe.java:173 (FQN TimeUnit); DetectionTest ×4 helper duplication (13); HaxeTestRunnerPipelineTest.java:283/341 (member order + duplicated compile tail); HaxeTestEventsConverterTest.java:20 (wrapped fixture strings)
- cross-cutting: SHA-256 cache-dir idiom hand-rolled ×3 (HaxeTestReporterFiles/AirTestHost/HaxeTestSingleRuns); "dev"/"git" literals ×11 vs HaxelibSemVer's constants

## questions

- 03: HaxeTestRunConfigurationEditor.java:58 — syncCompileStep parses build files on the EDT during dialog apply; deliberate?
- 11: intellij_munit/Macro.hx:44 — does munit's addTest first argument stay a String across the supported versions (else the filter silently drops every test)?
