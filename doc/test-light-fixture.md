# Light-fixture migration — inventory, pilot, and measurements

Status: MIGRATION COMPLETE AND VERIFIED — **full suite 1361 tests, 0
failures, 1 environmental skip (flash lane without AIR_SDK), in 3 m 6 s
vs the 6 m 26 s–6 m 39 s baseline: the suite HALVED.** Counts read from
build/test-results/test/TEST-*.xml (177 files, 1361/0/0/1). The A/B pilot
that proved the mechanism is below; the twins were deleted after the
in-place migration. Nothing committed.

Two issues surfaced by the first full run, both fixed:

- `HaxeRecursiveStdInferenceTest` read the `myHaxeToolkit` FIELD to locate
  ArraySort.hx; the hook replaced the `useHaxeToolkit()` call that set it.
  The test now computes the path via `HaxeTestUtils.getAbsoluteToolkitPath`.
- `HaxeTestGutterContextTest` (4 failures) was misclassified: its
  tests-build classpath claims resolve real paths, which the in-memory
  temp FS breaks. Reverted to heavy — it belongs with the v2 family.

## The migration mechanism (in place, tests untouched)

Everything light-specific lives in `HaxeLightFixtureTestCase`; the heavy
`HaxeCodeInsightFixtureTestCase` is untouched by the light world and stays
the default. A migrated class only changes its extends line:

```java
public class HaxeRenameTest extends HaxeLightFixtureTestCase {            // bare module
public class HaxeAnnotationTest extends HaxeToolkitLightFixtureTestCase { // + toolkit std
```

(29 suites are BARE vs 9 WITH_TOOLKIT.) `HaxeLightFixtureTestCase`
overrides `setUp` to build a CodeInsightTestFixture over the platform's
shared light project (reused while the descriptor is EQUAL — descriptors
are shared statics in `HaxeLightProjectDescriptors`) plus the same
root-access/recursion setup the heavy path does, and overrides `tearDown`
to drop temporary code-style settings (a leak only a shared project can
see). `HaxeToolkitLightFixtureTestCase` extends it and overrides only the
descriptor choice. Making light the DEFAULT of the heavy base was rejected:
it would silently flip the remaining heavy classes, including ones that
must stay heavy.

Conversion gotcha that cost a red run: a SUBCLASS hook is not redundant
until its base actually provides the same descriptor —
`HaxeSemanticAnnotatorTestBase` had no hook of its own (only its
subclasses did), so dropping the subclass hooks without converting the
base sent the 255-test annotator family back to heavy AND without the
toolkit (155 failures). The base now extends
`HaxeToolkitLightFixtureTestCase`.

Toolkit classes' `setUp` overrides that only did `useHaxeToolkit(); super.setUp();`
were REPLACED by a `WITH_TOOLKIT` hook; setUps with extra steps (style
settings, field init) kept those steps and lost only the toolkit line.
Test bodies were not touched anywhere.

Two shared-project safety changes rode along in the bases:

- `tearDown` now drops temporary code-style settings (several suites call
  `setTestStyleSettings`; a fresh heavy project used to hide the leak).
- `HaxeSemanticAnnotatorTestBase` no longer registers inspections directly
  into the project profile; it enables them through the fixture, which
  rolls them back per test (the profile would otherwise accumulate across
  the shared project — and each test's `unsetInspections` would stop
  meaning anything).

Migrated (35 classes / ~630 tests): GoToDeclaration, Rename, StubIndex,
FindUsages, ImportOptimizer, Deprecated/UnusedImport inspections,
SmartEnter, GoToImplementation/Super/SymbolContributor,
IsReferenceToSwitchCase, ResolveVariable, CodeFragment×2,
StringLiteralLink, FqnFileBasedIndex, VariableSourceNavigator,
HashLink×2, SetValueCompletionSuppression, TemplateFiles, TestFinder,
TypeAddImportIntention, LiveTemplates, Surround, Formatter,
HeavyHaxeQuickFix, Annotation, RecursiveStdInference, ParameterInfo,
CompletionTypeText (WITH_TOOLKIT over the base's BARE),
LanguageLevelGating, SemanticAnnotator (227), plus bases:
ResolveHighlighting (3 suites), TestFrameworkDetection (4 suites),
Introduce (variable, 25), Completion (all 9 suites).

Deliberately NOT migrated: HaxeAccessAnnotatorTest /
HaxeAllowAccessAnnotatorTest (left untouched per earlier decision),
HaxeUnusedAnnotatorTest (own profile-registration copy),
HaxeTargetOptionsTest (settings mutation), HaxeProjectTrustTest
(per-project trust state), HaxeMethodParameterInlayTest (inlay engine
handles its own fixture), HaxeTestGutterContextTest (real-path classpath
claims, see above), and the module/process/run-config families
(LibrarySync, LiveCompilerIntegration, BuildSystem, BuildFileSections,
TestRunnerPipeline, TestLaunchPlanner, TestLocator,
TestRunLineMarkerContributor, ProgramLaunches,
BrowserRunConfigurationPersistence, TestDebugConsoleSeam).

## Why

The JFR profile (doc/test-performance.md) measured 138 s of the 399 s suite
as `IndexingTestUtil.waitUntilIndexesAreReady` polling inside
`HaxeCodeInsightFixtureTestCase.setUp` — every heavy-fixture test opens a
fresh project and waits for it to be indexed (~120 ms), before the rest of
the open/dispose cost. The platform's light fixture keeps ONE project and
reuses it for every consecutive test whose `LightProjectDescriptor` is
EQUAL, recreating it only on a descriptor change — the per-test open and
index wait disappear.

## What the heavy base actually sets up (what light must replicate)

`HaxeCodeInsightFixtureTestCase.setUp` builds: a `HaxeModuleType` module,
the temp dir as source (or plain content) root, optionally the test
toolkit's std as a second source root (`myHaxeToolkit`), a
`CodeInsightTestFixture` over it, `VfsRootAccess` allowances, and two
`RecursionManager` assertion relaxations. **No SDK objects, no V1
structures** — all of it expressible in a `LightProjectDescriptor`.

## Pilot design

- `HaxeLightProjectDescriptors` — the shared descriptor instances (`BARE`,
  `WITH_TOOLKIT`); shared statics because project reuse is keyed on
  descriptor equality (`LightPlatformTestCase` keeps the project while
  `ourProjectDescriptor.equals(descriptor)`).
- `HaxeLightFixtures.setUpLightFixture(...)` — builds the light
  `CodeInsightTestFixture` (in-memory `LightTempDirTestFixtureImpl`), does
  the same root-access/recursion setup as the heavy base but bound to the
  per-test disposable (the shared project outlives each test).
- Pilot classes are **twins that extend the original test class** and
  override only `setUp()` — every `@Test` is inherited, so heavy and light
  run IDENTICAL test code and the timing difference is pure fixture cost.
  The heavy base's `tearDown` (`myFixture.tearDown()`) already does the
  right thing for a light fixture (rolls back per-test files, keeps the
  project).

| twin | tests | descriptor | exercises |
|---|---|---|---|
| `HaxeGoToDeclarationActionLightTest` | 65 | WITH_TOOLKIT | navigation + std resolution |
| `HaxeRenameLightTest` | 17 | BARE | document/file mutation + rollback |
| `HaxeStubIndexLightTest` | 32 | BARE | incremental indexing of per-test files |

Verification bar: the light twin must pass the SAME tests as its heavy
original (same executed count, zero failures) — a faster twin that changes
behaviour is a failed pilot.

## Inventory of the 55 heavy-fixture classes

Features: toolkit = mounts the std; module = touches module/roots APIs;
process = launches external tools; settings = mutates project settings
(needs per-test reset discipline under a shared project).

### Light candidates — pure configure-and-assert (the bulk)

| class | tests | notes |
|---|---|---|
| HaxeGoToDeclarationActionTest | 65 | toolkit — **pilot** |
| HaxeStubIndexTest | 32 | **pilot** |
| HaxeRenameTest | 17 | **pilot** |
| HaxeSemanticAnnotatorTest (base: SemanticAnnotatorTestBase) | 227 | biggest win; @Nested classes — migrate after pilot proves the mechanism |
| HaxeLanguageLevelGatingTest (same base) | 28 | |
| completion suites (HaxeCompletionTestBase family) | 121 | ReferenceCompletionTest 87 is @Nested-heavy; verify nested inheritance before twinning |
| HaxeAnnotationTest | 20 | toolkit |
| HaxeFormatterTest | 18 | uses temporary code-style settings — must stay per-test |
| HaxeParameterInfoTest | 16 | toolkit |
| HaxeStringLiteralLinkTest | 15 | |
| HaxeFqnFileBasedIndexTest | 14 | |
| HaxeVariableSourceNavigatorTest | 14 | |
| HaxeFindUsagesTest | 13 | |
| HaxeImportOptimizerTest | 13 | |
| HaxeDeprecatedInspectionTest | 12 | |
| HaxeTemplateFilesTest | 11 | |
| HaxeUnusedImportInspectionTest | 9 | |
| HashLinkSmartStepIntoOrderTest | 7 | |
| HashLinkExpressionQualifierTest | 6 | |
| HaxeAllowAccessAnnotatorTest | 6 | toolkit |
| HaxeSmartEnterTest | 6 | |
| HaxeTargetOptionsTest | 6 | settings — reset discipline |
| HaxeSurroundTest | 5 | |
| HaxeTestGutterContextTest | 5 | |
| HaxeAccessAnnotatorTest | 4 | toolkit |
| HaxeProjectTrustTest | 4 | trust state is per-project — verify isolation under reuse |
| HaxeTestFinderTest | 4 | |
| HaxeUnusedAnnotatorTest | 4 | toolkit |
| HaxeGoToImplementationTest | 4 | |
| detection suites (HaxeTestFrameworkDetectionTestBase) | 19 | |
| HaxeIntroduceVariableTest | 25 | |
| HaxeTypeAddImportIntentionActionTest | 3 | |
| ModuleCompletionTest / SyntheticCompletionTest / etc. | small | |
| HaxeGoToSuperTest, HaxeGotoSymbolContributorTest, HaxeLiveTemplatesTest, HaxeIsReferenceToSwitchCaseTest, HaxeResolveVariableTest, HaxeCodeFragment* | ≤2 each | long tail |
| HaxeRecursiveStdInferenceTest | 1 | toolkit; 10 s of genuine highlighting — fixture kind irrelevant |

### Stay heavy

| class | why |
|---|---|
| HaxeTestRunnerPipelineTest | launches haxe/neko/adl, copyDir, run configs |
| HaxeTestLaunchPlannerTest | process probes + settings |
| HaxeLiveCompilerIntegrationTest | module + live compiler |
| HaxeLibrarySyncTest | mutates library roots |
| HaxeBuildSystemTest, HaxeBuildFileSectionsTest | v2 build-file/module structure |
| BrowserRunConfigurationPersistenceTest, HaxeTestDebugConsoleSeamTest | run-configuration plumbing |
| HaxeProgramLaunchesTest, HaxeTestLocatorTest, HaxeTestRunLineMarkerContributorTest | run/launch surface |
| HeavyHaxeQuickFixTest | named heavy for a reason — verify before touching |

(resolve-highlighting suites — HaxeExpressionResolveTest etc. — are light
candidates too; base mounts the toolkit.)

## Caveats the migration must respect

- **Descriptor thrash:** classes alternate BARE/WITH_TOOLKIT → a project
  rebuild on each descriptor switch. Class-level, so at most a handful per
  run; grouping toolkit suites together in execution order would avoid even
  that.
- **Settings bleed:** temporary code style / compiler settings / trust
  state must be reset per test — the shared project remembers.
- **@Nested twins:** Jupiter discovery of nested classes inherited from a
  superclass needs verifying before twinning the @Nested-heavy suites.
- **The endgame is not twins.** Twins exist only for the A/B. The real
  migration flips the suite class's own setUp to the light path (or the
  heavy base grows a light mode the class opts into) — no duplicated runs.

## Results

One gradle run (`:cleanTest :test --no-build-cache`, single JVM), heavy
originals and light twins back to back. **Parity: every twin executed the
same test count as its original, 0 failures, 0 skips (242 total).**

| suite | tests | heavy wall | light wall | speedup | heavy median | light median |
|---|---|---|---|---|---|---|
| GoToDeclaration (WITH_TOOLKIT) | 72 | 32.1 s | 18.0 s | 1.8× | 434 ms | 114 ms |
| Rename (BARE) | 17 | 7.2 s | 2.9 s | 2.5× | 417 ms | 96 ms |
| StubIndex (BARE) | 32 | 12.8 s | 2.2 s | 5.9× | 381 ms | 55 ms |
| **combined** | **121** | **52.1 s** | **23.1 s** | **2.3×** | | |

The per-test medians are the honest number: **~400 ms heavy → 55–114 ms
light**, i.e. the ~300 ms fixture floor is gone. The wall-clock speedups
are dampened by one-time init that the first light test pays:

- GoToDeclaration's first test took 8.9 s — that is the WITH_TOOLKIT
  project creation INCLUDING indexing the toolkit std, paid once per run
  (heavy pays a smaller per-test slice 72 times instead). Excluding it,
  the remaining 71 light tests ran 8.7 s vs heavy's 31.3 s — 3.6×.
- Rename's first test paid 1.3 s — the BARE project creation.
- StubIndex's first test paid NOTHING (56 ms): it reused the BARE project
  the Rename twin had already built. **Cross-class project reuse works.**

Extrapolation: ~700–800 of the suite's 1361 tests are light candidates at
~300 ms saved each → roughly 3.5–4 minutes off the ~6.5-minute suite,
minus one ~9 s toolkit-project init per run. Consistent with the "roughly
halves the suite" estimate in doc/test-performance.md.

Worth knowing per suite: StubIndex gains the most (5.9×) because its tests
are index lookups — nearly all of its heavy time WAS the fixture. Rename
(document mutation + rollback) shows no shared-state issues across 17
mutating tests. GoToDeclaration proves std resolution works against the
descriptor-mounted toolkit.

## Are the tests actually checking anything? (mutation verification)

The "too good to be true" check: a test that only asserts "no error on
this statement" passes vacuously if the annotator never runs. Two answers:

**Static:** `testHighlighting` is TWO-SIDED — an expected `<error>` /
`<warning>` tag in the fixture that the annotator fails to produce FAILS
the test. So every fixture with expectation markup proves the machinery
ran. Markup coverage of the migrated highlight suites:

| fixture dir | configured fixtures | with markup |
|---|---|---|
| annotation.semantic | 231 | 169 (73 %) |
| annotation.languagelevel | 28 | 15 |
| annotation | 20 | 18 |
| annotation.unused | 6 | 3 |

The unmarked remainder are deliberate negative tests ("this construct must
NOT be flagged") — vacuous-passable by design, under heavy exactly as
under light; the migration did not change which tests are two-sided.

**Dynamic (mutation probes, run on the migrated light suites):**

- Probe 1 — `AnnotatorUtil.shouldSkip` forced to `true`, silencing every
  semantic annotator: **179 of 280 tests failed (64 %)** across
  SemanticAnnotator + LanguageLevelGating + Annotation + the resolve
  suites. Failures match the static counts exactly (LanguageLevelGating:
  15 failed = its 15 marked fixtures; the semantic nested groups sum to
  the 169 marked fixtures). The survivors are the negative tests plus
  HaxeExpressionResolveTest (its 6 tests assert successful RESOLUTION,
  whose failure mode is the unresolved-symbol inspection its sibling
  Import/Module tests — which DID fail — exercise positively).
- Probe 2 — `checkFile` of HaxeDeprecatedInspection and
  HaxeUnusedImportInspection forced to return null: **deprecated 12 of 12
  failed** (every test load-bearing), **unused import 5 of 9 failed** (the
  4 survivors are the "this import must NOT be flagged" negative tests —
  correct by design).

Both probes were working-tree-only and reverted after the runs.

## The light-fixture files

- src/test/java/com/intellij/plugins/haxe/HaxeLightProjectDescriptors.java — the shared BARE / WITH_TOOLKIT descriptors
- src/test/java/com/intellij/plugins/haxe/HaxeLightFixtureTestCase.java — light setUp/tearDown (the heavy base knows nothing about light)
- src/test/java/com/intellij/plugins/haxe/HaxeToolkitLightFixtureTestCase.java — descriptor override only

(The pilot's A/B twin classes and the interim `HaxeLightFixtures` util
were deleted once the in-place migration replaced them.)
