# Test-suite duplication & overlap review — 2026-08-17

Scope: all of `src/test` (166 files) and the debugger modules' tests (55 files across
7 modules; compat-matrix and hxcpp-debugger-protocol-legacy have no tests). Reviewed
for: duplicate/overlapping tests, parameterization candidates
(junit-jupiter-params 5.14.4 is on every module's test classpath — no build changes
needed), multi-concern tests to split, and helper/fixture duplication. House rules
respected: one concern per test (PSI-annotator tests exempt), per-debugger-module
request-factory copies are policy (flagged only when copies DRIFTED), fixture-driven
naming untouched. Every "identical/duplicate" claim below was verified by comparing
the actual assertions; the two highest-impact claims were independently re-verified.

Priorities: **P1** = fix soon (includes latent bugs), **P2** = worthwhile cleanup,
**P3** = optional / judgment call.

## A. Latent BUGS found in test code (fix first)

**Status 2026-08-17: ALL FIXED** — A1 via a guarded `continueToExit` on
`DapIntegrationTestBase` (three copies deleted); A2 via `assertSameElements`;
A3 via `LiveProbeUtil.spawnAdapterServer` (all five spawn sites routed through
it, drain guaranteed); A4 via `HxcppIntegrationTestBase.spawnDebuggee` (the
start-order test now extends the base and gains its teardown + diagnostics);
A5 by threading `expectedCount`; A6 deleted.

| # | Location | Finding | Suggested fix | Prio |
|---|---|---|---|---|
| A1 | `debuggers/hashlink-debug-adapter/.../dap/integration/EvalCallIntegrationTest.java:282` | `continueToExit` polls inside `while (true)` with **no null check** — a timed-out `pollEvent` returns null, falls through every `instanceof`, and the test hangs forever instead of failing. The sibling copies in `MutateIntegrationTest:154` and `ConditionalBreakpointsIntegrationTest:118` have the guard; this one drifted. | Move one guarded `continueToExit(int threadId)` into `DapIntegrationTestBase` (next to `continueToNextStop`), delete all three copies. | P1 |
| A2 | `src/test/.../haxelib/MetadataTest.java:94` | Hand-rolled `assertContains` has `assertEquals(list.size(), elements.length)` — expected/actual **reversed**, so a size mismatch reports backwards; also re-implements `UsefulTestCase.assertSameElements`. | Delete the helper; call `assertSameElements(...)` at the three call sites (:116, :121, :122). | P1 |
| A3 | `debuggers/browser-debugger/.../FirefoxAdapterLiveProbe.java:476` (`runSeparatorVariant`) and `BrowserTestCaptureLiveProbe.java:100` | Two of the five adapter-spawn copies **dropped the background pipe drain** the other three document as load-bearing ("so the adapter can't block on a full pipe") — one then runs an 8s event loop, the other a 60s capture loop, with stderr accumulating. | Add `LiveProbeUtil.spawnAdapterServer(command, workDir, expectedAnnouncement, tag)` (it already owns `killTree`/`connectWithRetry`) and route all five spawn sites through it. | P1 |
| A4 | `debuggers/vshaxe-hxcpp-debugger-adapter/.../HxcppStartOrderIntegrationTest.java:46` | Re-implements the base's debuggee spawn but its gobbler **discards every output line** — a failure here reports nothing, while the base accumulates into `debuggeeOutput` for diagnostics. The reordering the test exists for justifies a copy, not the drift. | Add `spawnDebuggeeOnly(String exeProperty)` to `HxcppIntegrationTestBase` (keeping the output sink) and call it before `adapter.start`. | P1 |
| A5 | `src/test/.../actions/HaxeTestFinderTest.java:173` | `doFindTestsTest(int i)` / `doFindClassesTest(int i)` ignore their parameter and hardcode `1` — a caller passing 2 silently asserts the wrong expectation. | Thread the parameter through (or drop it and the misleading signature). | P1 |
| A6 | `debuggers/browser-debugger/.../JsDebugAdapterLiveProbe.java:940` | `traceAdapter` is never set `true` anywhere — three dead branches (:949, :966, :976). | Delete the field and branches. | P2 |

## B. Duplicate / heavily overlapping tests

**BATCH 2 STATUS 2026-08-17** — B2, B3, B7, B17, B18 FIXED (B2: guard method
deleted, evaluation owners verified incl. the view-vs-evaluate distinction —
see the amended B2 row; B3: the two unique pins moved into
`MutateIntegrationTest.assignsThroughEvaluateExpression`, adjusted to the
values in force at that point (n=1 after the idx copy, so `arr[idx] = n + 89`
→ 90 and `flag = n < 10` → true); B7: rewritten as
`assertStoppedInHx(driveSessionToStop(buildFixture(), MAIN_HX, BP_LINE), ...)`;
B17: the wire test's in-handler expr re-pin dropped, zero-setVariable stays;
B18: the three re-pins trimmed to their unique assertions). B4/B5 FIXED in
the eval module (EvalLiveTest = raw-protocol pins only:
answers-while-suspended, breakpoint id, resume race; EvalExceptionLiveTest =
the stop's description + throwing line, stall coverage stays with
EvalStepExceptionLiveTest). B6 FIXED: the IDE-sequence probe keeps the
hydration but drops the duplicated first-stop assert and the four unasserted
staged counts with their 4s sleep; its pin is the second-pause targets.

**B1 WON'T DO 2026-08-17** — all changes reverted; the two access classes
stay exactly as they are. The finding itself was wrong on the merge (the
classes test different concerns: normal keyword rules vs the metadata that
overrides them), and two rounds of restructuring made things messier, not
better: the fixture framework copies each fixture into the test project at
its testData-relative path, so the `package accesscontrol` fixtures MUST be
configured with a directory prefix (the annotator flags the package/location
mismatch itself, verified by a failed flat-layout attempt) — which conflicts
with keeping fixture paths out of the tests. Leave `HaxeAccessAnnotatorTest`,
`HaxeAllowAccessAnnotatorTest`, `HaxeUnusedAnnotatorTest` and
`HaxeSemanticAnnotatorTestBase` untouched.

| # | Location(s) | Overlap | Suggested fix | Prio |
|---|---|---|---|---|
| B1 | `src/test/.../ide/HaxeAccessAnnotatorTest.java` ↔ `HaxeAllowAccessAnnotatorTest.java` | Two classes with identical imports, `setUp`, `doTest` overloads and `registerInspectionsForTesting`; only the fixture prefix (`accesscontrol/`) differs. Both also copy ~35 lines their package's `HaxeSemanticAnnotatorTestBase:49-92` already provides (as does `HaxeUnusedAnnotatorTest:41`). | Make all three extend `HaxeSemanticAnnotatorTestBase` (delete the local copies), then merge the two access classes into one (fixture prefix per method group, or move the 6 `accesscontrol/` fixtures up a directory). Coverage unchanged. | P1 |
| B2 | `debuggers/hashlink/.../PackagedStaticsIntegrationTest.java:53` `unqualifiedStaticsKeepWorkingElsewhere` | Runs TWO full debug sessions; both assertions duplicate EVALUATION-path owners at the same breakpoints (view-vs-evaluate distinction checked): instance-frame `evaluated("axes")` == `VariablesIntegrationTest.evaluatesThisFieldAndStatics` (same fixture/line/expression); static-frame `evaluated("version")` == `EvaluateExpressionIntegrationTest.staticFramesKeepCombiningStatics`, which pins BOTH the unqualified and `Config.`-qualified forms there. Qualified-from-foreign-frame stays owned by `evaluatesClassQualifiedStatics`; the Statics-scope VIEW facts are separately owned by `readsStaticFields`. | Delete the method. | P2 |
| B3 | `debuggers/hashlink/.../VariablesIntegrationTest.java:711` ↔ `MutateIntegrationTest.java:55` | Both pin "assignment through evaluate writes a local and reads back" on the same fixture/line. | Delete `assignsExpressionResults`; move its two unique pins (computed-index LHS, boolean RHS) into `MutateIntegrationTest.assignsThroughEvaluateExpression`. | P2 |
| B4 | `debuggers/eval/.../EvalLiveTest.java:100` ↔ `EvalDebugAdapterLiveTest.java:56` | Same fixture, same breakpoint line, same four pins (frame line, source suffix, `greeting` local, exit 0) — raw protocol vs DAP layer. | Trim `EvalLiveTest` to its protocol-only unique pins (threads-while-suspended, breakpoint id, resume-race exception); DAP test keeps the rest. | P2 |
| B5 | `debuggers/eval/.../EvalExceptionLiveTest.java:30` ↔ `EvalStepExceptionLiveTest.java:100/:223` | Same fixture, same `uncaught` filter, same stop→hydrate→continue→terminate tail in two classes. | Keep `EvalExceptionLiveTest` as the correctness pin (description + throw line); delete its tail, which `EvalStepExceptionLiveTest.hydrateStopLikeTheIde` covers with timing on top. | P2 |
| B6 | `debuggers/browser/.../JsDebugAdapterLiveProbe.java:260` ↔ `:416` | The `targets.size() >= 2` wire fact pinned twice on the same fixture/line. | Drop the `n1 >= 2` assertion at :416; that probe's unique pin is the second-pause `n5 >= 2`. Also: stages 2–4 (:401–:412) compute counts that are never asserted — assert the invariant (`n2 == n1`, `n4 == n1`) or delete them with their `Thread.sleep(4_000)`. | P2 |
| B7 | `debuggers/browser/.../JsDebugAdapterLiveProbe.java:712` `fullSessionWithChildViaStartDebugging` | 116 hand-rolled lines asserting only what the class's own `driveSessionToStop` helper asserts on every call. | Rewrite as one `assertStoppedInHx(driveSessionToStop(...), MAIN_HX, BP_LINE)` or delete. | P2 |
| B8 | `src/test/.../v2/testing/run/HaxeTestLaunchPlannerTest.java:423` | `testTestDebuggabilityFollowsTheSharedTargetSupport` re-derives matrices owned by `HaxeDebugSupportTest` (target→debuggable) and `HaxeBuildSystemTest` (build→target); production is literally the composition. | Shrink to the two things nobody else covers: hxml-no-target → interp fallback, and one lime selection flip proving the CURRENT selection is read. Delete the hl/neko/swf/js/nme rows. | P2 |
| B9 | `src/test/.../v2/testing/run/HaxeTestLaunchPlannerTest.java` :112, :582, :590 | Single assertions re-pinning rules owned by sibling tests (`single.n` redirect owned by :93/:124; tool-forwarding spellings owned by :511/:569). | Delete the re-asserted lines; each test keeps only its named concern. | P2 |
| B10 | `src/test/.../v2/buildtools/settings/HaxeTestsBuildFileStoreTest.java:74-75` | `staleMarkedPathsDropOut`'s tail duplicates `everyConventionalCandidateIsSuggested` (:92). | Keep only the stale-path assertion (:73); delete :74-75. | P2 |
| B11 | `src/test/.../haxelib/HaxelibLocalDocsTest.java:27` ↔ `:38` | Both bottom out in the same private `devDirectory`; identical setup. | Merge into one test covering both entry points; unique null-case stays. | P2 |
| B12 | `src/test/.../runner/debugger/hashlink/HashLinkExpressionQualifierTest.java:116` ↔ `:178` | For fragments, `HaxeAddImportHelper.addImport` just calls `fragment.importClass` — the second test re-asserts the first one layer up. | Narrow :116 to the no-import baseline (rename `testBareFragmentDoesNotResolveAnUnimportedClass`); :178 is the positive test. | P2 |
| B13 | `src/test/.../runner/debugger/HaxeCodeFragmentReparseTest.java:41` ↔ `:58` | Six-edit test subsumes the single-edit test. | Move the `getFirstChild` assertion into the repeated-edits test; delete the other; extract a `typeInto` helper. | P2 |
| B14 | `src/test/.../runner/debugger/HaxeCodeFragmentCompletionTest.java:59` ↔ `:65` | Both run `fragmentCompletions("this.")` — two fixture builds for one result set. | Merge into `testThisCompletionListsOwnAndInheritedMembers` (one `assertContainsElements` with all four names). | P2 |
| B15 | `src/test/.../hashlink/HashLinkSmartStepIntoOrderTest.java:147` | `"f2(...)"` row re-proves the paren-stripping the js-debug integration test (:130) proves end-to-end. | Drop the row; the unit test keeps the dot-dialect forms. | P3 |
| B16 | `src/test/.../ide/HaxeCompilerErrorParsingTest.java` ↔ `compiler/HaxeCompilerMessageTest.java` | Both drive `HaxeCompilerMessage.create` on the same output shapes asserting the same five properties. | Fold the `ide/` cases into `HaxeCompilerMessageTest` (its `doTest` helper is already parameterized; expose rootPath) and delete the `ide/` copy. | P2 |
| B17 | `debuggers/vshaxe/.../HxcppDebugAdapterTest.java:560` ↔ `:581` | Unit test already pins `topLevelAssignment("n == 100") == -1`; the wire test re-pins the decision. | Narrow the wire test to its non-redundant assertion: `setVariable` was never called on the wire. | P3 |
| B18 | `debuggers/hashlink/.../VariablesIntegrationTest.java` :792-795, :813, :406 | Small re-pins of array-write-through-evaluate, map-get, and box-content already owned elsewhere in the same class. | Trim each to its unique pin (details in agent notes: negative "arrays are NOT maps" assertion; `set/get("c")` round-trip; `captured` child exists). | P3 |
| B19 | `src/test/.../v2/buildsystem/HaxeBuildFileSectionsTest.java:98-99` | Section-ids tail re-asserts what `HxmlFileParserTest:98` owns (incl. the `#2` suffix). | Delete the two lines. | P3 |

## C. Parameterization candidates (@ParameterizedTest available everywhere)

**C1 FIXED 2026-08-17** — new `HaxeTestFrameworkReportingArgsTest` (plain unit
test, no fixture): one `@MethodSource` table (framework, live args, toggled-off
args, without-root args) over all four frameworks, one parameterized
no-filter-define test over buddy/munit/tink, named tests for utest's
`filterArgs` and its batch floor. The four detection classes keep only their
PSI-detection tests.

**BATCH 2 STATUS 2026-08-17** — C2, C3, C4 FIXED (VariablesIntegrationTest:
12-row container table + 3-row leaf table, each row keeping its runtime-layout
comment; SteppingIntegrationTest: both closure pairs as `@ValueSource` over
the two call lines, static-vs-deferred facts moved to the class javadoc;
HlBuildSnifferTest: `fromArguments` as a 5-row `@CsvSource` incl. the null
row). C10 PARTIAL: the 9-row `topLevelAssignment` table converted to
`@CsvSource`; the three scripted trios stay named — each pins a distinct
concern (path building / numeric index / read-back frame) with its own
handler-side assertions, and one table would need per-row post-conditions.
C11 WON'T DO: the three typedthrow tests pin different MECHANISMS (startup
thrown filter + ctor-frame trimming; typed filter via the class chain; typed
negative) and even differ in how filters are wired — a table hides that.
C12 WON'T DO (P3): each probe writes its own fixture with a distinct
upstream-behaviour pin (diagnostic / limitation / working case) and the
fixture-above-test layout is the house style for these probes.

| # | Location | Shape today | Suggested fix | Prio |
|---|---|---|---|---|
| C1 | `src/test/.../v2/testing/{Buddy,Munit,Tink,Utest}DetectionTest` reportingArgs tests | Same four assertions ×4 classes with different literals, same tripled comment; none touch `myFixture`. | New plain `HaxeTestFrameworkReportingArgsTest`: one `@ParameterizedTest @MethodSource` over (framework, expectedLiveArgs, togglesInjection); one named test for utest's unique `filterArgs`. Removes ~45 duplicated lines. | P1 |
| C2 | `debuggers/hashlink/.../VariablesIntegrationTest.java` (9 "reads X" methods + 3 scalar methods) | Every one is findVariable → assert preview → assert child map (or one scalar). | Two `@ParameterizedTest`s: `@MethodSource` (name, preview, childMap) for the 9; `@CsvSource` (name, value) for the 3. Keep runtime-layout comments as row labels. | P2 |
| C3 | `debuggers/hashlink/.../SteppingIntegrationTest.java` (2 byte-identical pairs) | Bodies differ only by a line constant. | Two `@ParameterizedTest @ValueSource(ints=...)` methods (4→2); move the static-vs-deferred comments to class javadoc. | P2 |
| C4 | `src/test/.../runner/debugger/hashlink/HlBuildSnifferTest.java` :22-:44 | Four methods over `fromArguments(String)` differing only in data. | One `@ParameterizedTest @CsvSource` (args, expectedBytecode, expectedOutput). Hxml-file tests stay. | P2 |
| C5 | `src/test/.../haxelib/HaxelibSemVerTest.java` :13/:53/:42 | 13 inline predicate assertions + 6 `create` mappings; failures report no input. | `@ValueSource` accepted/rejected pairs (+`@NullSource`); `@MethodSource` for the create→constant map. | P2 |
| C6 | `src/test/.../hashlink/HashLinkExpressionQualifierTest.java` :52-:80 | Four tests on one fixture, pure in→out rewrite pairs. | One `@CsvSource` with 4 rows. Do NOT fold in :142/:156/:208 (different fixtures). | P2 |
| C7 | `src/test/.../v2/buildtools/NmeProjectsTest.java` :16/:25/:42/:50 | Four identical 3-line bodies over `targetArtifact`. | One `@CsvSource` (flag, app, root, target, expectedOutput). Host-dependent cpp/neko tests stay. | P2 |
| C8 | `src/test/.../v2/testing/run/HaxeTestLocatorTest.java` :40-:68 | Six locate→(kind, name) cases over four methods. | One `@CsvSource` (reportedName, expectedKind, expectedName). Tie-break and negative tests stay. | P2 |
| C9 | `src/test/.../v2/buildsystem/HxmlFileParserTest.java:51` | Hand-rolled Map+forEach parameterization — loses per-case names on failure. | Convert to `@CsvSource` with a HaxeTarget converter. | P2 |
| C10 | `debuggers/vshaxe/.../HxcppDebugAdapterTest.java` :578 (9 rows) and :258/:364/:592 (3 scripted trios) | Same pure function ×9; same script skeleton ×3. | `@CsvSource` (expression, index) for the first; `@MethodSource` (scopeTree, childName, expectedExpr, frameId) for the second. | P2 |
| C11 | `debuggers/intellij-hxcpp/.../ExceptionsIT.java` :90/:120/:137 | Same scenario, differing (filters, filterTypes, outcome). | `@MethodSource` (filters, filterTypes, expectedStopText-or-null). | P2 |
| C12 | `debuggers/browser/.../JsDebugAdapterLiveProbe.java` :492/:527/:552 | Three step-in-targets probes differing in (source, line, expected count incl. one `== 0` limitation pin). | `@MethodSource` with an IntPredicate expectation per row. | P3 |
| C13 | `debuggers/dap-protocol/.../DapJsonTest.java` | Decode-dispatch spread over 8 methods at three granularities. | One `@MethodSource` (json, expectedClass) for pure dispatch (~13 types); body-field assertions stay in named tests. | P2 |
| C14 | `src/test/.../actions/HaxeGoToDeclarationActionTest.java:468-545` | Seven copy-paste object-literal-key tests differing in 3 strings. | `assertKeyResolves(fixture, field, typedef)` helper or `@ParameterizedTest`; the two extra-assert variants pass a flag. | P2 |
| C15 | `src/test/.../v2/buildtools/settings/HaxeTargetOptionsTest.java:30`, `runconfig/HaxeDebugSupportTest` (whole file), `display/HaxeGeneratedDumpServiceArgsTest:62/:69`, `hashlink/HashLinkBackendScopeTest` | Smaller matrices (2–5 rows each). | Optional `@CsvSource` conversions; do only where the matrix reads better than named cases. | P3 |

**BATCH 3 STATUS 2026-08-17** — B8–B14, B16 FIXED (B8: the debuggability test
keeps its two planner-owned pins — interp fallback and the current-selection
flip; B9: the three re-asserted lines deleted; B10 tail deleted; B11 merged
into `testDevPointerFileDrivesBothDevEntryPoints`; B12 narrowed to the
no-import baseline with the positive half pointed at the addImport test; B13
merged with a `typeInto` helper; B14 merged into own+inherited; B16: the
`ide/` copy deleted, its cases folded into `HaxeCompilerMessageTest` via a
`doRootedTest` helper, the two file-less-warning spellings as a
`@ValueSource` pair, the platform-conditional absolute-path case named, and
the permanently disabled windows test dropped). C5–C9, C14 FIXED — all on
`@FieldSource`/`@ValueSource` tables per the structured-sources rule. D1–D4,
D7–D8 FIXED (D1 as the three suggested tests sharing a `limeSingleRunCompile`
helper + a `DISPLAY_MODE_ARGUMENTS` constant; D3's trust tests share
`withDistrustedProject`). E8–E10 FIXED (`contextAtCaret()` on the fixture
base; the shared `instanceFrameProject` in a new `HaxeDebuggerTestFixtures`
holder; `HaxelibLocalDocsTest` on `@TempDir`).

## D. Multi-concern tests to split

| # | Location | Concerns bundled | Suggested split | Prio |
|---|---|---|---|---|
| D1 | `v2/testing/run/HaxeTestLaunchPlannerTest.java:138` (13 assertions) | Entry-point swap; output redirect; reporter macro forced+deduped; workdir/connectEligible; display-argument scrubbing. | 3 tests: swap+redirect / reporter-macro-once / scrubs-display-arguments. | P2 |
| D2 | `v2/testing/run/HaxeTestLaunchPlannerTest.java:342` | Implicit first-section default AND stored section selection. | `chainedHxmlDefaultsToItsFirstSection` + `storedSectionSelectionPicksTheSiblingSection`. | P2 |
| D3 | `v2/buildtools/HaxeProjectTrustTest.java:37` | `isTrusted`, `checkForBackgroundEvaluation`, server refusal — three entry points. | Two tests + a shared setTrusted/restore helper. | P2 |
| D4 | `v2/buildtools/settings/HaxeEnvironmentStoreTest.java:36` | put-add/update + remove. | Move :48-49 into `removeDefineDropsTheEntry`. | P2 |
| D5 | `debuggers/hashlink/.../VariablesIntegrationTest.java:576` (6 concerns) | Foreign-frame statics; packaged path; HFun regression; static WRITE; bare-class container; unknown-root error. | 4 tests as listed in agent notes. | P2 |
| D6 | `debuggers/eval/.../EvalDebugAdapterLiveTest.java:411` | Element write by bracket name AND whole-array replacement/fresh reference. | Split off `wholeArrayReplacementCarriesAFreshReference`. | P2 |
| D7 | `haxelib/HaxelibLocalDocsTest.java:65`, `HaxelibSemVerTest.java:22/:33`, `HaxelibLibraryInfoTest.java:35` | Packed-refs + detached HEAD; toString round-trip + precedence; parse + render; fields + releases + colon rule. | Splits as named in agent notes (2/2/2/3 tests). | P2 |
| D8 | `runner/debugger/HaxeVariableSourceNavigatorTest.java:104` | Index uniqueness, FQN module segment, runtimeClassName stripping. | Two tests + shared `ancillaryProject()` fixture helper. | P2 |
| D9 | `v2/testing/TinkDetectionTest.java:29`, `display/HaxeGeneratedCodePreviewSanitizeTest.java:26`, `hashlink/HashLinkBackendScopeTest.java:30` | Positive+negative bundled; unrelated backtick rule tacked on; sibling-reject + prefix-trap. | Split the odd assertion out of each (sibling classes already model the right shape). | P3 |

## E. Helper / fixture duplication (within-module; cross-module copies are policy)

**BATCH 2 STATUS 2026-08-17** — D5, D6 FIXED (`evaluatesClassQualifiedStatics`
split into foreign-frame reads / static write round-trip / bare-class
container / unknown-root error; the whole-array replacement split out of the
bracket-name test). E2–E7 FIXED with two adjustments: E3's base gained
`awaitEvent(Class)` + `awaitOutputContaining` (used by `awaitStopped`, Pause
and Lifecycle) and `scopeByPrefix` uses the `scopesRequest` factory — but the
loops in AttachMode, BreakpointReflush, ConditionalBreakpoints and the
Lifecycle scenario test STAY: they carry scenario logic (stop counting,
fail-on-stop, two signals in any order) a generic helper would weaken. E5's
worker fixtures were NOT unified: they drifted (`counter` vs `ticks`), each
side's line constants are hand-synced to its own tests, and no Firefox lane
exists here to verify a merge — the byte-identical `WEB_MAIN_HX`/`WEB_LOAD_HX`
(+ MAIN_HX/LOAD_HX names and BP_LINE/LOAD_BP_LINE) moved into `LiveProbeUtil`,
and `compileHaxeJs` gained the (timeout, extraArgs) overload that
`compileWithReporter` now rides.

**E1 FIXED 2026-08-17** — `EvalLiveTestBase` gained `initialize()` (with the
IDE's adapter id), `setBreakpoints(file, lines...)`, `runToBreakpoint(file,
lines...)`, `startSession(filters)`, `topFrame(threadId)` and
`findLocal(frameId, name)` (returns the variable plus its scope reference);
every inline copy and the three private per-class helpers are gone. Verified
live: all 36 eval-debugger tests ran against the real VM, 0 failures.

| # | Location | Finding | Suggested fix | Prio |
|---|---|---|---|---|
| E1 | `debuggers/eval` — 13 inline copies of the initialize→launch→setBreakpoints→configurationDone dance (8 in `EvalDebugAdapterLiveTest` alone) + 3 private per-class helpers re-implementing each other | The module base lacks what `DapIntegrationTestBase.runToBreakpoint` provides its sibling. | Add `initialize()`, `setBreakpoints(file, lines...)`, `runToBreakpoint(file, line)`, `startSession(filters)` to `EvalLiveTestBase`; delete all copies. Also hoist `findLocal(frameId, name)` (3 copies) and `topFrame(threadId)` (2 copies). | P1 |
| E2 | `debuggers/eval/.../EvalLiveTest.java:162/:171` | `haxeOnPath()`/`fixtureDir()` byte-copies of the base's (class doesn't extend it — raw-protocol test). | Call the base's protected statics directly or move to a tiny `EvalFixtures` holder. | P2 |
| E3 | `debuggers/hashlink/.../DapIntegrationTestBase.java:612` + 6 hand-rolled poll loops across 5 classes | Base's own `scopeByPrefix` ignores its own `scopesRequest` factory; siblings solved the poll loop once (`awaitEvent`). | Use the factory; add `awaitEvent(Class)` + `awaitOutputContaining(String)` to the base and collapse the loops. | P2 |
| E4 | `debuggers/browser/.../FirefoxAdapterLiveProbe.java` :301/:403/:495/:916 | Four inline `SetBreakpointsRequest` builds despite the class's own helpers AND `LiveProbeUtil.breakpointsRequest`. | Use `LiveProbeUtil.breakpointsRequest` (add a raw-path overload for the separator test). | P2 |
| E5 | `debuggers/browser` — `WEB_MAIN_HX`/`WEB_LOAD_HX` duplicated between the two probes; worker fixtures DRIFTED (`counter` vs `ticks`, hand-synced line constants); `compileWithReporter` re-implements `compileHaxeJs` with a different timeout | Shared fixtures belong in `LiveProbeUtil` (which already owns `INDEX_HTML` for this reason). | Move the shared fixture sources + line constants into `LiveProbeUtil`; give `compileHaxeJs` timeout + extraArgs parameters. | P2 |
| E6 | `debuggers/vshaxe` — `HxcppPauseIntegrationTest:41-52` inline request builds; `HxcppLaunchIntegrationTest:160/:169` identical bodies; `continueQuietly:65` rebuilds `sendContinue`'s request | Base factories exist for all three. | Use `stackTrace`/`sendContinue`; keep one `evaluateRequest`; extract `continueRequest(threadId)` in the base. | P2 |
| E7 | `debuggers/intellij-hxcpp/.../ToStringRenderingIT.java:30/:76` | Six-line session preamble duplicated between the two tests. | `stopAtToStringLine(session)` helper mirroring `SmartStepIT.stopAtSmartLine`. | P3 |
| E8 | `src/test` — caret-context idiom (`findElementAt(caret)` + same assert message) duplicated 8× across `HashLinkExpressionQualifierTest` (6 sites), `HaxeCodeFragmentCompletionTest`, `HaxeVariableSourceNavigatorTest` | The base has no such helper. | Add `protected PsiElement contextAtCaret()` to `HaxeCodeInsightFixtureTestCase`; in the qualifier test also generalize `importingContext()` to `contextIn(String mainSource)`. | P2 |
| E9 | `HaxeCodeFragmentCompletionTest.frameContext()` ↔ `HaxeVariableSourceNavigatorTest.instanceFrameProject()` | Same project fixture (Widget.hx identical, Base.hx differs by one member), same javadoc sentence. | One shared `instanceFrameProject()` (Base with both members) in a small debugger-test fixtures helper. | P2 |
| E10 | `src/test/.../haxelib/HaxelibLocalDocsTest.java` (8 sites) | Only file still hand-rolling `Files.createTempDirectory` (never deleted); every sibling uses `@TempDir`. | Convert to `@TempDir Path temp` + a `write(relative, content)` helper. | P2 |
| E11 | `src/test/.../resolve/` — `HaxeExpressionResolveTest`/`HaxeImportTest`/`HaxeModuleTest` | Byte-identical setUp + highlighting doTest ×3 (no base exists; two classes carry one test each). | Only if adding a small `HaxeResolveHighlightingTestBase` to that package feels worth it — 3 copies is the threshold. | P3 |
| E12 | `v2/testing/run/HaxeTestLaunchPlannerTest.java:326/:593` | Live-reporting save/restore scaffolding repeated (now consistent after the test-code batch, still duplicated). | `withLiveReportingDisabled(Runnable)` helper. | P3 |

## F. Misfiled coverage (not duplication)

| # | Location | Finding | Suggested fix | Prio |
|---|---|---|---|---|
| F1 | `runner/debugger/HaxeVariableSourceNavigatorTest.java:194` | `testRealFileThisMemberInsideAnObjectLiteralResolves` never touches the navigator — it asserts plain PSI resolution; nothing under `resolve/` covers this case. | Move to `HaxeResolveTest` (unique coverage, wrong home). | P3 |

## Verified clean (no action)

- **hashlink-debug-adapter**: 14 of 22 test classes clean, incl. all four exception-filter
  classes (each pins a different filter/fixture) and `DebugAdapterIntegrationTest`.
- **intellij-hxcpp-debugger**: `RunControlIT`, `BreakpointsAndEvaluateIT`, `SmartStepIT`,
  `ClosureStepIT`, `FixtureSession` — the module's helper factoring is the model to copy.
- **dap-protocol**: framing/client/evaluation-path tests clean; the three same-named
  framing test classes across modules test three genuinely different wire formats.
- **v2**: events-converter (10 distinct rewrite rules), debug-console seam, gutter
  context vs line-marker tests (different ownership modes — correctly layered), all
  seven settings-store test classes, buildsystem parsers, `info`/`libraries` tests.
- **runner/debugger**: expression-qualifier branch tests (:52/:142/:156/:208 hit four
  distinct resolver branches), variable-source-navigator positive/negative pairs,
  `HlExecutableResolverTest` precedence rungs, persistence and console-bridge tests.
- **Legacy**: `lang/parser/**` (bases properly shared), `lang/completion/**`,
  `lang/psi/**` index tests (same-named methods hit three different indexes),
  `ide/inlay|quickfix|inspections|refactoring|references|info/**`, `actions/move/**`.
- Unit-vs-pipeline layering is correct: the seam/pipeline tests re-touch unit-tested
  behavior only as pre-flight guards inside expensive live runs (kept deliberately).

**BATCH 4 STATUS 2026-08-17** — B15 WON'T DO and F1 WON'T DO (user call:
both stay as they are). E11 FIXED strictly as a setup extraction:
`HaxeResolveHighlightingTestBase` owns the shared setUp + doTest; the three
classes keep their base paths and tests byte-identical. D9 FIXED (tink
positive/negative split; the backtick rule out of the package test; the
prefix-trap out of the sibling-reject test). E12 FIXED
(`withLiveReportingDisabled`). C15 PARTIAL by design: only
`HaxeDebugSupportTest` converted — its whole file is now one
(target, program, test) lane table; the 2-row matrices elsewhere stay named
tests, where they read better. B19 LEFT AS-IS (user call; it had slipped out
of the batch-4 listing and a follow-up attempt was reverted — the two
sectionIds lines stay). THIS CLOSES THE REVIEW: all 61 findings are fixed,
won't-do, or (B19) deliberately left.

## Suggested batching

1. **Bugs + top structural wins (P1)**: A1–A5, B1, C1, E1.
2. **Debugger-module cleanup (P2)**: B2–B7, B17–B18, C2–C4, C10–C13, D5–D6, E2–E7.
3. **src/test cleanup (P2)**: B8–B14, B16, C5–C9, C14, D1–D4, D7–D8, E8–E10.
4. **Optional (P3)**: everything else, case by case.
