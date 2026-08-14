# Test-runner support — research summary and implementation plan

Companion research (all verified, cited):
[test-runner-research-frameworks.md](test-runner-research-frameworks.md),
[test-runner-research-vscode-adapter.md](test-runner-research-vscode-adapter.md),
[test-runner-research-integration.md](test-runner-research-integration.md).

## Research in three sentences

**Frameworks:** utest dominates (683k downloads, the only actively maintained
framework, used by both reference projects) and ships a built-in TeamCity
service-message reporter (`-D teamcity`) plus a single-test filter
(`-D UTEST_PATTERN=<Class.method>`); munit/buddy are large but dormant;
hexunit/haxe.unit are dead. **VSCode:** the vshaxe adapter supports six
frameworks by macro-patching their runners from a shipped haxelib and passing
results through `.unittest/results.json` — powerful but self-admittedly
fragile, file-bound (sys/node targets only), and with **zero lime/openfl
support**. **Our plugin:** the compile-and-run pipeline (reference-based run
configs, `resolveAction` + `extraArguments`, `HaxeProgramLaunches` target
dispatch, debug additions) fits test running almost unchanged; the greenfield
pieces are the SM console, test detection, gutter markers, the tests-buildfile
marker, and a test run-configuration type.

## Core architecture decisions

### 1. Result channel: stdout TeamCity service messages
IntelliJ's test view (SMTRunner) natively parses `##teamcity[...]` from the
process stdout. utest emits exactly that with `-D teamcity`. Both reference
projects confirm exit codes are unreliable across targets (starling's lime
window never exits; crypto never calls `Sys.exit`), so stdout messages are the
correct channel regardless. The vshaxe file-based channel is NOT adopted for
v1; it remains the documented fallback mechanism for frameworks without a
TeamCity reporter (see Phase 4).

**Live events via macro injection, batch as fallback** (evolved over the
crypto test drives): utest's TeamCity reporter prints the whole event batch
at run end — no live tree, 0ms durations, stdout unattributable. Ruled: a
vshaxe-style `--macro intellij_utest.Macro.init()` (shipped sources in
`resources/testing/utestLiveReporter/`, extracted by
`HaxeTestReporterFiles`) patches `utest.Runner`'s constructor to attach a
LiveReporter riding the public `onTestStart`/`onTestComplete` dispatchers —
per-test events stream as tests run: live tree, real durations, natural
output attachment. Every failure mode degrades to batch behavior (absent
utest → inert metadata; missing dispatchers/odd ctor → macro warns and
no-ops; extraction failure/setting off → no injection), because
`-D teamcity` stays on and the events converter deduplicates the batch's
end-of-run replay against already-completed tests. A Build Tools | Haxe
setting ("Live test reporting", default on) disables the injection. For
batch-shape runs the converter additionally does BEST-EFFORT output
attribution: trace position prefixes (`src/.../Blake2sTest.hx:78:`) resolve
through PSI to the containing test method (`HaxeTestOutputAttributor`) and
buffered lines replay as `testStdOut` inside the started/finished pair.
(An earlier classpath-shadowed TeamcityReport was rejected as too covert;
the explicit macro + setting + graceful fallback superseded it.)

### 2. Framework parity with VSCode; utest-native first, adapter-backed rest
GOAL (ruled): match the VSCode Test Explorer's six-framework coverage
(utest, munit, buddy, hexUnit, tink_unittest, haxe.unit). Route: utest gets
the native integration (its built-in TeamCity reporter is strictly better
than any injection); the other five arrive through ONE mechanism — the
vshaxe `test-adapter` haxelib, shipped with the plugin like
LimeProjectParser and consumed via its documented `.unittest/` protocol
(positions.json for discovery, results.json watched and translated into SM
events, filter.json for single-test runs). We reuse their maintained
per-framework injectors instead of rebuilding six fragile patch sets.

The `HaxeTestFramework` abstraction therefore carries a RESULT CHANNEL as
part of the contract — `STDOUT_TEAMCITY` (utest) vs `RESULTS_FILE`
(adapter-backed) — and detection is per-framework in shape: base-type
walks (utest ITest, haxe.unit TestCase), metadata (`@Test` munit,
`@:describe` tink), name-convention (munit's `~/Test/`), and suite-classes
only for buddy (string-named `describe`/`it` closures cannot map to PSI
methods — class-level gutters only, matching VSCode's granularity there).
Framework selection is auto-detected from the tests build file's `-lib`s,
user-overridable on the run configuration.

utest as the only fully-native v1 implementation:
- **detect**: class implements `utest.ITest` or extends `utest.Test`
  **transitively** (crypto's `unit.Test` base proves direct-extends matching
  is insufficient) — served by the existing inheritance stub index; test
  methods = `test*`/`spec*` prefixes.
- **activate**: append `-D teamcity` to the tests build.
- **filter**: append `-D UTEST_PATTERN=<pattern>` for class/method runs.
- **locate**: TeamCity test names → PSI via the FQN/member indexes.

### 3. The tests build file (user's proposal, adopted)
A per-container "tests build file" marked in the Haxe tool window — same
store/tree/action pattern as the active-build-file concept
(`@Storage("haxeBuildConfig.xml")` + `HaxeBuildSettingsListener.TOPIC`,
tree badge, context action). It supplies the libs/defines/target/classpaths
for every test run. Convention-based suggestion on first use: `test.hxml`,
`tests/*.hxml`, `tests/project.xml` when present.

### 4. Runs reuse the existing pipeline
A new `HaxeTestRunConfiguration` (reference-style: container + tests build
file + optional filter pattern) resolves through the same
`HaxeCompileCommands`/`HaxeProgramLaunches` machinery as action configs —
compile the tests build file with the framework defines appended, launch the
artifact per target, attach an `SMTRunnerConsoleView` instead of the plain
console. Rerun-failed = rerun with a `UTEST_PATTERN` built from failed test
names.

### 5. Gutter runs come from the marked tests file, not wrappers (v1)
With a marked tests build file, running one class/method is just the full
tests build + `UTEST_PATTERN` — no wrapper generation, correct libs/defines
by construction. The wrapper-compile idea (generate a TestMain, compile via
`contextFor`'s resolved context) is deferred to Phase 4 as the fallback for
projects with NO tests build file; it is strictly more fragile (must
reconstruct the test classpath) and UTEST_PATTERN makes it unnecessary for
the common case.

### 6. Tool window surface (proposed, pending final look)
Primary: a **"Run Unit Tests" action node under the marked tests build
file** — the established actions-under-build-file grammar, but dispatching
to the test run configuration (SM console) instead of the plain
HaxeCommandRunner console, mirroring how Build-and-Run actions dispatch to
run configurations. Secondary: a container-row context action resolving to
the marked file ("mark a tests build file first" hint when unmarked).
Naming trap: lime's default `test` action (build-and-launch) is unrelated —
the unit-test entries are bundle-keyed "Run Unit Tests" with the platform
test icon, never bare "test". Gutter (Phase 2), tree, and container actions
all converge on the same run-configuration producer.

## Phases

### Phase 1 — utest on hxml, the full vertical slice
1. `HaxeTestsBuildFileStore` (+ tree badge, "Mark as Tests Build File"
   context action, convention-based suggestion).
2. `HaxeTestFramework` interface + utest implementation (detection via
   inheritance index; activation/filter defines).
3. `HaxeTestRunConfiguration` + producer (from the tests file node) with
   SM console: `SMTestRunnerConnectionUtil`, console properties, and an
   `SMTestLocator` resolving `ClassName.methodName` through the member
   indexes for click-through.
4. Target gate: run only host-executable targets (interp, neko, hl, jvm,
   desktop cpp; js via node when configured) — clear error message otherwise
   (html5/browser arrives in Phase 3). js-under-node detection: a tests build
   carrying `-lib hxnodejs` / `-D nodejs` declares node-runnability (and with
   it utest reports to stdout and exits 0/1 properly); plain js without it
   still yields console output under node, so it runs with a hint suggesting
   hxnodejs. hxnodejs is also the prerequisite for the vshaxe-adapter
   fallback channel on js (its file I/O needs node) — relevant to Phase 4
   munit support, whose own js runs go through node for the same reason.
5. Debugging spike: prototype attaching SM console output processing to a
   DAP-owned debug session (HashLink config as the guinea pig). Outcome
   decides whether tests-with-debugging ships early (Decision 2).
6. Verification: fixture project mirroring crypto's layout (per-target
   hxml + unit.TestMain + a project-local Test base); live test asserting
   SM events arrive (testStarted/testFailed with diff attributes).

### Phase 2 — gutter markers and single-test runs
1. `RunLineMarkerContributor` on detected test classes/methods (transitive
   inheritance check, dumb-aware degradation).
2. Producer creates/reuses a `HaxeTestRunConfiguration` with the pattern
   set; naming `<Class>` / `<Class.method>`.
3. Rerun-failed action from collected failures.

### Phase 3 — lime/openfl/nmml tests (the starling case; our differentiator)
1. ✅ Define injection through the tools, VERIFIED LIVE (R1 resolved):
   - lime 8.3.2 forwards CLI args into its generated hxml — ATTACHED defines
     only (`-Dname=value`; the two-word `-D name=value` spelling duplicates
     the value), `--source=<dir>` becomes `-cp`, `--haxeflag=<flag>` lands
     verbatim (carries the live-reporter `--macro`).
   - nme 7.0.64 forwards attached defines (spaced values intact) and any
     double-dash token verbatim as a haxeflag line; single-dash haxe flags
     (`-cp`) are swallowed — the classpath rides `--class-path <dir>`.
   Full injection proven end-to-end: a lime neko app streamed live reporter
   events with output attribution, batch replay following.
2. ✅ Launch via packaged-artifact layout knowledge:
   `LimeProjects.packagedBinary` (`<app path>/<target>/bin/<app file>[.exe]`,
   host targets incl. the self-contained HL package) and
   `NmeProjects.targetArtifact` (now covering neko's
   `<root>/<host>-neko/<app>/<launcher>`). Windowed apps that never exit are
   accepted — the SM tree completes from the `##teamcity` stream.
3. Starling-shaped verification is a sandbox check (tests/project.xml +
   TestMain wiring utest).
4. REMAINING: html5/browser research + implementation — utest's reporter
   output in the JS console, piped from the browser debug backend's CDP
   capture into the SM stream; lime desktop-cpp test DEBUGGING (likely free
   through the hxcpp lane's debug additions — needs a sandbox verification
   before the Debug action enables for lime files).

### Phase 4 — framework parity via the adapter backend
GOAL (ruled): match the VSCode Test Explorer's six-framework coverage.
- Ship the vshaxe `test-adapter` haxelib (pinned version + SHA-256 per the
  supply-chain rules; applied as `-lib test-adapter` + its `--macro` init,
  exactly as VSCode does) and implement the `.unittest/` bridge: a
  results.json watcher translating into SM events, a filter.json writer for
  single-test runs, positions.json as the discovery fallback where PSI
  detection falls short.
- Per-framework `HaxeTestFramework` implementations for munit, buddy,
  tink_unittest, hexUnit, haxe.unit — detection per their shapes (metadata,
  name conventions, base types), channel = RESULTS_FILE, granularity
  documented per framework (buddy: class-level gutters only — string-named
  closures cannot map to PSI methods; VSCode has the same ceiling).
- Sequenced by adoption: munit → buddy → tink_unittest → hexUnit/haxe.unit
  (the last two are dead upstream — support is whatever the adapter gives,
  no bespoke work).
- Also in this phase (unchanged): wrapper generation for gutter runs without
  a marked tests file (generate TestMain into a scratch dir, compile against
  `contextFor` context); tests-with-debugging if the Phase 1 spike deferred
  it.

## --next chains (shipped)

Live-verified semantics (haxe 4.3.7): `--next` sections are fully isolated
sequential compilations — nothing is inherited across them; the only sharing
mechanism is `--each`, whose preceding lines apply to every section. Display
mode answers from the FIRST section only, and trailing CLI arguments after
the hxml file land in the LAST section only. vshaxe punts on chains entirely
(its docs warn against `--next`/`--each` in configurations).

Ruled: the aggregator must NOT merge across `--next`; sections are separate
builds; sections are split via the real HXML grammar, not a hand-rolled
line scanner; the ROOT file's chain entries are the units, each expanded
individually. Shipped design:

- `HaxeBuildFileInspector.sectionContents(project, file)` splits the ROOT
  file on `--next` via the HXML PSI (the grammar), applies the `--each`
  block, then flattens each section's includes INDEPENDENTLY — a file every
  section shares expands into every one (a whole-file flatten's cycle guard
  used to swallow the later expansions). An included file's internal
  `--next` stays inside its section: a prep-step + build pair is one
  selectable unit, and a scoped compile still runs both (haxe splits the
  section's own `--next` itself). Empty sections (a lone trailing `--next`)
  are compiler no-ops (verified live) and produce no entry.
  `inspect` = first section (compiler display parity), `inspectSections` =
  all of them.
- `HaxeSectionSelectionStore` (workspace file) remembers the section a
  build file follows BY IDENTITY (`HxmlFileParser.sectionIds`: leading
  include filename, `#n` for repeats), so chain edits keep the selection on
  the same section and a removed section falls back to the first.
  `HaxeBuildSections` is the project-aware accessor. The tool window shows
  a "Section" selector row under multi-section hxml files (labels are
  FILENAMES, never targets); tree info, library sync, define context,
  debug additions and the test planner all follow the selection.
  Source-roots offers union ALL sections (the module holds every section's
  sources).
- Test compiles are SECTION-SCOPED: the before-run task (and the interp
  single-stage plan) replaces the hxml file token with the selected
  section's lines as CLI arguments, so the injected reporting/debug flags
  belong to that section instead of the chain's last one — and sibling
  sections don't compile at all during a test run.
- Test-run entries moved to a dedicated "Tests" category node under the
  tests build file (the Actions group was getting crowded).
- Deferred: per-section Build (single) / Build all actions.

## Phase 4: multi-framework (munit + buddy + tink_unittest shipped)

Architecture RULED OUT the vshaxe test-adapter results-file protocol
entirely: every framework reports TeamCity service messages on stdout,
through a shipped reporter when the framework has none (the
`ResultChannel.RESULTS_FILE` stub is deleted). `HaxeTestFramework` gained
`libraryName()` (detection from the tests SECTION's `-lib` declarations,
`HaxeTestLaunchPlanner.frameworkFor`), `supportsInterp()`, and
`reportingArgs(suiteName, reporterClasspath, liveReporting)`; utest is the
default when no framework lib is declared. For munit/buddy the shipped
reporter IS the result channel, so the live-reporting toggle does not
disable it (utest's stays optional over its own batch).

munit wire facts (verified live, munit 2.3.5):
- `massive.munit.TestRunner` has public `addResultClient`; the shipped
  `intellij_munit.Macro` ctor-patches it exactly like the utest injection,
  attaching a `LiveClient` implementing the base `ITestResultClient` (the
  richer interfaces are younger - base keeps old munit compiling). Events
  fire live but only AFTER each test: adjacent started/finished pairs with
  the measured duration.
- munit HANGS on the eval interpreter (predates it) - the planner refuses
  interp/no-target munit builds with a bundle error.
- The classic TestMain exits 0 even on failures (its `Timer.delay`ed
  completion handler misses the process end) - verdicts are events-only.
- munit redirects `trace()` into its clients (PrintClient prints them in
  the end summary); PrintClient writes progress glyphs without line breaks,
  so every service message starts on a fresh line of its own.
- No filter define; single-test runs need a generated TestSuite (Phase 2).

buddy wire facts (verified live, buddy 2.13.0):
- `-D reporter=<fqcn>` swaps the reporter - NO macro patching; the shipped
  `intellij_buddy.TcReporter` emits one TeamCity BATCH from `done()`:
  `progress(spec)` carries no suite context, only the finished tree has the
  describe-nesting, per-spec durations (`spec.time`, seconds) and captured
  traces (replayed as that spec's `testStdOut` - attribution without stdout
  parsing). Root suite name bakes in via a macro function reading
  `teamcity_suite_name` (runtime code cannot read defines; the macro helper
  lives in an UNguarded class - `#if !macro` hides a class from the macro
  interpreter).
- Suites are prose describe-strings; specs are closures with no method PSI
  (`isTestMethod` always false - the VSCode adapter shares that ceiling).
  Detection: transitively extends `buddy.BuddySuite` or implements
  `buddy.Buddy`.
- Runs on interp; the generated main exits 1 on failure. A crashed describe
  body surfaces as one synthetic failed test. No filter define
  (`@include` metadata is code-side).

nme × frameworks (verified live, nme 7.0.64): nme ships its own
`haxe.Timer` override whose native-target class lacks the std static
`stamp`/`delay`/`stop`. utest and tink_unittest call those statics in their
runners and DO NOT COMPILE under nme at all (with or without the IDE);
munit and buddy are unaffected. The user-side fix is a shim classpath in
the TESTS nmml (a copy of the std Timer listed after the nme haxelib — the
LAST classpath wins module resolution), documented in the testProjects
utest/tink nme samples. The plugin never injects it: it changes the test
binary's timer semantics away from nme's frame-loop-integrated one, which
is the user's call, and the compile errors without it are loud and
explicit. The clean fix is upstream (nme adding the std statics).

tink_unittest wire facts (verified live, tink_testrunner 0.9.0):
- `Runner.run(batch, ?reporter)` defaults a null reporter to BasicReporter;
  the shipped `intellij_tink.Macro` build-patches that default to the IDE's
  TcReporter — an UNMODIFIED TestMain gets TeamCity reporting, one passing
  its own reporter keeps it. No user-code contract needed (unlike buddy's
  packaged mains).
- Reporter's ReportType stream carries suite/case starts and finishes;
  CaseResult has no timing, so durations stay unreported. `Runner.exit`
  reports the failure count as the exit code.
- Detection: `@:asserts` classes; tests = their public instance methods.
  Filters are `@:include/@:exclude` metadata, code-side (Phase 2 concern).

Remaining: munit/buddy/tink debug-lane sandbox verification.

## Risks / open verifications

- **R1**: does `-D name` pass through `lime build` to the haxe invocation for
  project.xml builds (and equivalently for nme)? Verify live before Phase 3;
  fallback: inject via a generated extra hxml/`<haxedef>` overlay.
- **R2**: utest TeamCity reporter fidelity vs IntelliJ's SM parser (attribute
  escaping, nested suites, async timing) — Phase 1's live fixture test is the
  gate; discrepancies get a thin stdout post-filter in the process listener.
- **R3**: `UTEST_PATTERN` granularity (class vs method matching semantics) —
  verify against utest source during Phase 1.
- **R4**: windowed lime apps may buffer stdout differently per target —
  Phase 3 fixture verifies HL + desktop cpp at minimum.

## Decisions (ruled)

1. **Runnable targets**: host-executable targets in Phase 1
   (interp/neko/hl/jvm/desktop-cpp, js under node). **html5/browser tests are
   IN SCOPE as a Phase 3 research+implementation item** — starling's
   `<template path="templates" if="html5"/>` shows real projects run them.
   Note: the vshaxe adapter CANNOT do browser tests (its file-based result
   channel needs sys/node file I/O), so this is another differentiator. The
   candidate mechanism is ours alone: utest's TeamCity reporter prints to the
   JS console in a browser; the plugin's existing browser debug backend (CDP)
   already captures console output — piping captured `##teamcity[...]`
   console lines into the SM stream would make browser test runs first-class.
   Verify the reporter's browser output path (trace vs console) during
   Phase 3 research.
2. **Debugging tests**: RESOLVED — spike verdict CHEAP, shipped with
   Phase 1. The debuggee is runner-spawned in our DAP lanes, so its stdout
   already flows through the session ProcessHandler; `DapTestConsoles`
   builds the SM console from the test configuration's properties inside
   `XDebugProcess.createConsole()`, and the platform attaches it before
   `startNotify()` — full lifecycle, zero adaptation. Shipped lanes,
   dispatched by plan target in `HaxeTestDebugRunner`: HL (artifact,
   `hl --debug --debug-wait`), interp (the compile IS the debuggee — eval
   lane, `-D eval-debugger` against the in-process adapter) and desktop C++
   (embedded intellij-hxcpp-debug-server, compiled in by the before-run
   step's debug additions — `HaxeDebugAdditions` now covers CPP for hxml
   builds, closing that phase-C gap for action configs too). js and flash
   are the deferred lanes: both route program output through the DEBUG
   connection (CDP console, fdb) instead of process stdout, so they need
   debugger output events replayed into the process handler before the SM
   console can parse the TeamCity stream (js also needs the node-vs-browser
   decision; flash additionally has no plain RUN story yet — its traces land
   in flashlog.txt, not stdout). Debugger chatter bypasses the SM parser via
   `ConsoleView.print`. Live breakpoint sessions are a sandbox check (no HL
   runtime in the dev harness).
3. **Detection**: classes implementing `utest.ITest` — **transitively**
   (`utest.Test` itself implements ITest, so one root covers both the
   interface and base-class styles; crypto's project-local `unit.Test` base
   resolves through the inheritance chain).
4. **Framework order**: utest (most popular by 2.4x and the only maintained
   one) fully first; munit/buddy later via the pluggable interface;
   hexunit/haxe.unit not planned (dead).
