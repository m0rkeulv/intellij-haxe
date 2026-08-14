# Test-runner Phase 1, first slice — implementation notes

Scope delivered: `HaxeTestsBuildFileStore` (+ tests), tool window marking
(context action + tree badge), `HaxeTestFramework`/`UtestFramework`
(+ detection tests). Run configuration, SM console and gutter markers are the
next slices.

## Judgment calls

- **`resolveOrSuggest` fallback order**: stored choice (when still among the
  candidates) → a file named `test.hxml`/`tests.hxml` (case-insensitive) →
  the FIRST candidate under a `tests/` directory (candidates arrive in the
  tree's name order). The plan's "any build file under tests/" is taken
  literally; for a crypto-style `tests/compile-*.hxml` fan the suggestion is
  therefore the alphabetically first per-target file — a deliberate
  best-effort default the user overrides by marking explicitly.
- **The suggestion shows as the badge**: the tool window model resolves the
  tests path through `resolveOrSuggest`, so a container with an unmarked but
  conventional tests file already renders the `(Tests)` badge — mirroring how
  `resolveActivePath` auto-activates a single build file without marking.
- **Badge style**: a grayed `(Tests)` suffix fragment on the build-file row,
  matching the existing `(Active)` suffix; a file can carry both. No icon
  variant — the sibling active-file marking uses text, so this does too.
- **Action shape**: one toggle action (`HaxeMarkTestsBuildFileAction`) whose
  label switches mark/unmark in `update()`. Registered programmatically in
  `HaxeToolWindowPanel.createTreePopupGroup` — the tree popup group is built
  in code, not in plugin.xml (`HaxeSetActiveBuildFileAction` precedent).
- **`HaxeTestFramework` kept to the four seam methods** (detect ×2, activate,
  filter). `locate` (TeamCity name → PSI) from the plan joins the interface
  with the SM locator in the run-configuration slice, when its signature is
  driven by a real caller. No framework registry/extension point yet — one
  implementation does not earn one.
- **Detection walks the class model**, not indexes:
  `getExtendingTypes()` + `getImplementingInterfaces()` →
  `HaxeClassReferenceModel.getHaxeClassModel()`, visited-set keyed by
  qualified name, depth bound 32. Dumb mode returns false up front, and an
  `IndexNotReadyException` mid-walk (dumb mode can begin between the check
  and the resolve) also degrades to false instead of throwing.
- **`isTestClass` requires `isClass()`**: interfaces (including `utest.ITest`
  itself), typedefs, enums and abstracts are never test classes even when
  they appear in an ITest inheritance chain.
- **`isTestMethod` checks the name prefix first** (cheap, no PSI resolution),
  then dumb mode, then public/instance/non-constructor via the member model,
  then the declaring class. Constructors are excluded explicitly even though
  `new` never matches the prefixes — the model check documents intent.
- **Fixtures stub utest**: `testData/testing/` carries minimal
  `utest/ITest.hx` + `utest/Test.hx` stubs plus the crypto-shaped
  project-local `unit/Test.hx` base, so detection is proven over a
  two-hop transitive chain without depending on the real haxelib.

# Phase 1b — run configuration, SM console, tree action

Scope delivered: `ResultChannel` on the framework seam, `HaxeTestRunConfiguration`
(+ type/factory/runner/editor, registered in plugin.xml), the SM test console
with locator and locationHint injection, the "Run Unit Tests" tree node +
container context action, and the fixture-backed planner/locator/pipeline tests
(`v2/testing/run` in src and test). Gutter markers and rerun-failed stay Phase 2.

## utest TeamcityReport findings (verified against the reporter source)

`utest/ui/text/TeamcityReport.hx` (master):

- Events: `testSuiteStarted`/`testSuiteFinished`, `testStarted`, `testFinished`
  (success), `testIgnored`, `testFailed name=… message=… details=…`.
- Naming: root suite `Target: <TargetName>` (or the `teamcity_suite_name`
  define); class suite `<package with dots replaced by underscores>.<ClassName>`;
  test `<class suite>.<methodName>`. The default package yields an EMPTY leading
  segment (`.MyTest.testX`).
- **No `locationHint`, no `flowId`.** Escaping only rewrites `'` → `|'`
  everywhere and newlines/CRs in `details`; `|`, `[`, `]` are NOT escaped
  (upstream bug — a value containing them can break parsing; R2's stdout
  post-filter remains the future fix if it bites).
- The whole report is emitted at COMPLETE time through one `trace(getResults())`
  call — messages arrive as one batch after the run, not streamed per test. The
  report starts with a newline so `##teamcity[` always begins a line despite
  trace's position prefix.
- Side find: the reporter's target-name switch tests `#if hs` for HashLink
  (typo upstream, actual define is `hl`), so HL runs report `Target: Undefined`.

## Judgment calls

- **locationHint injection over locator-only**: `SMTestProxy.getLocation`
  consults the locator ONLY when the proxy carries a location URL, and utest
  emits none — so without injection the tree could never navigate. The blessed
  seam is `SMCustomMessagesParsing`: `HaxeTestConsoleProperties` returns a
  `HaxeTestEventsConverter` whose `processServiceMessages` override rewrites
  `testStarted`/`testSuiteStarted` lines lacking a hint to carry
  `locationHint='haxe:test://<name>'` (the name is reused TC-escaped, so the
  new attribute stays valid). `HaxeTestLocator` then resolves the name: literal
  FQN, underscores-to-dots package variant, and a simple-name-index fallback
  filtered through the reporter's own naming (for packages containing real
  underscores).
- **2026.2 SM API check**: `SMTestRunnerConnectionUtil.createAndAttachConsole(
  String, ProcessHandler, TestConsoleProperties)` is current;
  `createConsoleWithCustomLocator` is `@Deprecated(forRemoval)`;
  `GeneralToSMTRunnerEventsConvertor` is `@ApiStatus.Internal` (NOT used — the
  pipeline test records events through a public `GeneralTestEventsProcessor`
  subclass instead).
- **Compile via the before-run step, not inline**: `ExecutionManagerImpl` runs
  `RunProfileState.execute` on the EDT, so a synchronous compile inside
  `startProcess` would freeze the UI. Artifact targets therefore reuse
  `HaxeActionBeforeRunTaskProvider.Task` exactly like `HaxeProgramLaunches`
  (Build-view output, off-EDT), with the framework defines as the task's extra
  arguments; `HaxeTestRunConfiguration.syncCompileStep()` keeps the task
  aligned with the file/filter. Interp builds get NO task — the compile IS the
  run, and it must happen inside the SM-consoled process.
- **No `--connect` for the test process**: with the compilation server an
  `--interp` run executes inside the server and its stdout never reaches the
  run console. The before-run compile step keeps its usual connect injection.
- **Artifact launch dispatch**: `HaxeProgramLaunches` maps target outputs to
  DEBUG run configurations (HashLink/Browser/...) that own their console — a
  test run must own the process to attach the SM console, so the planner
  (`HaxeTestLaunchPlanner`) derives plain host commands instead: `hl <.hl>`,
  `neko <.n>`, `java -jar <.jar>` (--jvm only), `node <.js>` (node required on
  PATH; hint when hxnodejs/`-D nodejs` is absent), `<cpp-out>/<Main>` (hxcpp
  names the binary after the main class — `HxmlFileParser.mainClass` was added
  for that). Everything else is a bundle-keyed error; lime-family tests files
  are refused until Phase 3. The output-path resolution mirrors
  `HaxeProgramLaunches.resolvedOutput` (relative to the build file's directory).
- **"Producer"**: `HaxeTestRunConfigurations.findOrCreate/run` is the single
  dispatch the tree row, the container action (and later the gutter producer)
  converge on — configurations matched by tests-file path, filter updated per
  run, mirroring `executeAction`'s find-or-create shape. A platform
  `RunConfigurationProducer` (PSI-context based) arrives with the Phase 2
  gutter work.
- **Container context action**: the panel remembers the last scan's
  per-container tests path (marked or convention-suggested), so
  `HaxeRunUnitTestsAction.update` stays cheap on the EDT; disabled with a
  "mark a tests build file first" description when the container has none.
- **Live test without utest**: the pipeline fixture's `TestMain` prints the
  reporter's exact message shapes under `#if teamcity` (proving the activation
  define reached the compile) and the test streams the real process output
  through the real converter into a recording processor — SM events + injected
  locations asserted end-to-end with no haxelib dependency.

## Debugging spike

**Verdict: CHEAP — ship with Phase 1/2.** Prototype landed: `DapTestConsoles`
(new), the `DapDebugProcess.createConsole` branch, `HaxeTestDebugRunner`
(new, HL lane), a planner `hlArtifact` helper, two bundle keys, one plugin.xml
line, and the replay test `HaxeTestDebugConsoleSeamTest` — 4 files of real
code plus registrations.

### Where debuggee stdout actually surfaces (premise correction)

The HL DAP flow does NOT deliver debuggee stdout via DAP output events. The
IDE runner spawns the debuggee itself (`hl --debug <port> --debug-wait`,
`DapDebugRunnerBase.doExecute`) and the adapter merely ATTACHES by pid — the
debuggee's stdout is inherited by the runner's `ColoredProcessHandler`, which
is the debug session's process handler. The adapter emits `output` events
only for its own diagnostics (breakpoint-condition failures, inspector
warnings) and for the launch-mode pumps the IDE never uses
(`DebugSession.hx` wires the stdout pump to `EvOutput` only in
`spawnAndHandshake`). So the TeamCity stream already flows through the exact
`ProcessHandler` an SM console attaches to — no replay handler needed.

### The seam (verified against the 2026.2 sources)

- `XDebugSessionImpl.init` does `myConsoleView = process.createConsole() as
  ConsoleView`; `BaseTestsOutputConsoleView implements ConsoleView`, so an SM
  console is a legal session console.
- `XDebuggerManagerImpl.startSession` calls
  `debugProcess.getProcessHandler().startNotify()` AFTER `initializeSession`
  (which runs `createConsole()`), so the SM converter attached inside
  `createConsole` sees the full lifecycle: `startNotified` → `startTesting`,
  `onTextAvailable` → parse, `processTerminated` → flush + `finishTesting`
  (`SMTestRunnerConnectionUtil.attachEventsProcessors`).
- DAP `terminated` needs no extra mapping: `DapDebugProcess.terminateSession`
  → `teardown()` → `processHandler.destroyProcess()` → `processTerminated`.
- Debugger chatter cannot corrupt the SM stream: `DapDebugProcess.print`/
  `printSystem` write through `ConsoleView.print`, which bypasses the
  converter (only `ProcessHandler` text is parsed).

Seam implementation: `DapTestConsoles.createTestConsole(profile, executor,
handler)` builds `SMTestRunnerConnectionUtil.createAndAttachConsole` from the
run profile's `SMRunnerConsolePropertiesProvider` (which
`HaxeTestRunConfiguration` already implements — same properties, converter,
locator and locationHint injection as the plain run);
`DapDebugProcess.createConsole` prefers it over the plain console. This is
profile-driven, so EVERY DAP lane gets the behavior for free the moment a
test configuration reaches its runner.

`HaxeTestDebugRunner` (Debug executor on `HaxeTestRunConfiguration`, HL plans
only) reuses the HashLink lane wholesale: `HashLinkBackend` + the
`hl --debug <port> --debug-wait <artifact>` command from the test plan; the
existing before-run compile step builds the artifact for the debug executor
too (before-run tasks are executor-independent). Non-HL plans get a
bundle-keyed error at execute time.

### Blast radius for the other lanes

- **hxcpp (both flavors) and eval/interp**: free. Their runners also spawn
  the debuggee (`createCommandLine` non-null), so stdout flows through the
  session's process handler and the same console branch works unchanged; each
  lane only needs its own `HaxeTestDebugRunner`-style mapping from the test
  plan to its backend/command line.
- **browser/CDP (Phase 3 html5)**: one extra change. The adapter owns the
  debuggee (`createCommandLine` returns null, `DefaultDebugProcessHandler`),
  and console output arrives as DAP `output` events which
  `DapDebugProcess.print` writes straight to the console view — bypassing the
  SM parser. Routing output-event text through
  `processHandler.notifyTextAvailable` when an SM console is attached feeds
  the parser; termination mapping already works (teardown destroys the
  handler). Estimated at a few lines in `handleOutput` plus the Phase 3
  research item on where utest's reporter output lands in a browser.

### Verified boundary

No HL runtime in this environment (`hl` not on PATH, no `HASHLINK_BIN`), so
the live end-to-end (real adapter, breakpoints suspending a test) was not
run. What IS verified: `HaxeTestDebugConsoleSeamTest` drives the exact seam
(`DapTestConsoles` console, attach → `startNotify` → output → terminate, the
XDebugger ordering) with the utest reporter's recorded batch shape split
mid-message, and asserts the parsed SM tree: suites, pass/fail states,
injected `haxe:test://` locations. The remaining live risk is small and
HL-specific (the batch report arriving while a breakpoint holds the process —
it simply arrives when the run resumes and completes); first sandbox session
with an HL toolchain should confirm breakpoint + tree together.
