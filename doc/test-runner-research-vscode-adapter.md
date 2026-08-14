# Test-runner research: vshaxe/haxe-test-adapter (VSCode)

Research notes for adding test-running support to the IntelliJ Haxe plugin, based on
reading the actual source of https://github.com/vshaxe/haxe-test-adapter (master,
extension v3.0.1, Jan 2025). Everything below is **verified from source** unless
marked *(inference)*. File citations use repo-relative paths; raw content at
`https://raw.githubusercontent.com/vshaxe/haxe-test-adapter/master/<path>`.

## 1. Architecture: how it hooks into the user's test build

The mechanism is **a haxelib whose `extraParams.hxml` runs an initialization macro**,
injected into the user's ordinary test command:

- The `test-adapter` haxelib ships *inside* the VSCode extension
  (`test-adapter/haxelib.json`). On activation (and whenever the extension path
  changes), the extension runs `haxelib dev test-adapter "<extensionPath>/test-adapter"`
  in a terminal so the lib resolves locally
  (`src/HaxeTestController.hx`, `updateHaxelib()`, lines 637–653).
- The test run is just the user's own build command with `-lib test-adapter`
  appended. Default setting (`package.json`):
  `"haxeTestExplorer.testCommand": ["${haxe}", "test.hxml", "-lib", "test-adapter"]`
  — `${haxe}` is replaced with vshaxe's configured `haxe.executable`.
- `test-adapter/extraParams.hxml` contains exactly one line:
  `--macro _testadapter.Macro.init()`. Adding the lib therefore activates the
  adapter with no other project change.
- `_testadapter/Macro.init()` (`test-adapter/_testadapter/Macro.hx`) then uses
  `Compiler.addGlobalMetadata` to attach `@:build` / `@:autoBuild` macros to
  **framework classes** (e.g. `massive.munit.TestRunner`, `utest.Runner`,
  `buddy.SuitesRunner`, `hex.unittest.runner.ExMachinaUnitCore`,
  `tink.testrunner.Runner`, `haxe.unit.TestRunner`) — i.e. it patches the *test
  framework's own runner classes at compile time*, not the user's code.
  Patching is expression surgery via `test-adapter/_testadapter/PatchTools.hx`
  (`patch(Start|End|Replace)` on a method body, `addInit` on constructors).
- `init()` also enforces minimum framework versions via `Context.fatalError`:
  munit 2.3.2, utest 1.13.0, buddy 2.10.0, hexunit 0.35.0, tink_testrunner 0.8.0,
  instrument 1.3.1 (`Macro.hx` lines 49–55). It bails out entirely under
  `Context.defined("display")` (no side effects for IDE completion), and skips
  writing positions when `--no-output` is present (compilation-server cache runs,
  `Macro.hx` lines 66–72).

What the user must change:
- Have a `test.hxml` that **compiles and runs** the tests (convention; configurable).
- `haxelib install json2object` (the lib's only dependency; README "Usage").
- Add `.unittest/` to `.gitignore` (README).
- For *debugging* only: add `-lib test-adapter` manually to the hxml, since the
  launch configuration can't be injected into (README "Debugging").

## 2. Supported frameworks and per-framework adaptation

Six frameworks (README "Features"): **munit, utest, buddy, hexUnit, tink_unittest,
haxe.unit**. Each gets a macro `Injector` (compile-time patch) plus, for most, a
runtime listener that the injector instantiates and registers:

| Framework | Injector (compile-time) | Runtime result listener |
|---|---|---|
| munit | `_testadapter/munit/Injector.hx` — patches `TestRunner.new` (register client), `TestRunner.executeTestCases` (Replace, coverage hooks), `TestClassHelper.addTest` (filter early-return), `scanForTests` | `munit/ResultClient.hx` implements `IAdvancedTestResultClient`/`ICoverageTestResultClient`; per-test `testResults.add(...)`, `save()` on finish |
| utest | `_testadapter/utest/Injector.hx` — patches `utest.Runner.new` (attach Reporter), `addCase` (utest 2.x) / `addITest` (1.x) replaced to filter fixtures + coverage setup/teardown wrapping | `utest/Reporter.hx` implements `IReport` via `ResultAggregator`; maps `Assertation` enum to states, `save()` in `complete()` |
| buddy | `_testadapter/buddy/Injector.hx` — patches `SuitesRunner.new` (wrap the user's reporter), `mapTestSpec` (record positions at runtime from `pos`), `BuddySuite.it/xit` (filter early-return); adds `fullDescribePath` helper | `buddy/Reporter.hx` implements `buddy.reporting.Reporter`, delegates to the wrapped reporter, `save()` at end; pending specs get `" (PENDING)"` postfix |
| hexUnit | `_testadapter/hexunit/Injector.hx` — patches `ExMachinaUnitCore.new` (add listener) and `run` (filter class/method descriptor lists at runtime) | `hexunit/Notifier.hx` implements `ITestClassResultListener` |
| tink_unittest | `_testadapter/tink_unittest/Injector.hx` — patches `tink.testrunner.Runner.run` (wrap reporter) and `runCase` (filtered cases return immediate success) | `tink_unittest/Reporter.hx` (+ `AttributableReporter.hx` for coverage) implements `tink.testrunner.Reporter` |
| haxe.unit | `_testadapter/haxeunit/Injector.hx` — renames `TestRunner.run` to `__run`, adds a new `run()` that calls it then reads `result.m_tests` via `@:access`; `buildCase` renames filtered-out `testX` methods to `disabled_testX` | none (results harvested from `TestResult` after run) |

There is also `instrument/Injector.hx` for attributable-coverage hooks in the
`instrument` coverage library.

## 3. Result channel: JSON files in `.unittest/`, watched by the IDE

**Files on disk, no socket, no stdout protocol.** Constant
`Data.FOLDER = ".unittest"` (`test-adapter/_testadapter/data/Data.hx`), relative to
`Sys.getCwd()` of the test process (baked in at compile time via `$v{Sys.getCwd()}`
in the injectors). Three files:

- `.unittest/results.json` — written by the runtime listeners via
  `TestResults.save()` (`data/TestResults.hx`); pretty-printed
  `Json.stringify(content, "\t")`. Shape (`data/Data.hx` typedefs, verified):

  ```json
  {
    "name": "root",
    "classes": [
      {
        "id": "pack.TestCase",             // SuiteId string; buddy: "[file] describe path"
        "name": "pack.TestCase",
        "pos": { "file": "src/pack/TestCase.hx", "line": 4 },   // 0-based
        "methods": [
          {
            "name": "testFoo",
            "state": "success",            // success | failure | error | ignore
            "message": "assert text or null",
            "timestamp": 1234.5,           // haxe.Timer.stamp()
            "executionTime": 12.0,         // optional, ms
            "line": 10,                    // optional, 0-based
            "errorPos": { "file": "...", "line": 12 }  // optional, failure location
          }
        ]
      }
    ]
  }
  ```

- `.unittest/positions.json` — test discovery data written at *compile* time
  (section 4).
- `.unittest/filter.json` — include/exclude list written by the IDE before a
  filtered run (section 5).

IDE side: `HaxeTestController` creates a `FileSystemWatcher` on
`**/.unittest/results.json` (`RelativePattern`, lines 84–87) and re-parses the
file on create/change (`onResultFile` → `TestResults.load` with a json2object
parser). Results also reload on task end. `TestResults.add` *merges*: an existing
method entry with the same name is replaced, so a filtered run only updates the
tests that ran; when a run has **no** filters, the patched runner constructors
clear `results.json` first (`PatchTools.addInit`, lines 28–38). This is why stale
results survive across filtered runs — by design.

The whole channel requires the test target to write files: every I/O site is
guarded `#if (sys || nodejs)` — the source of the README's "Currently only works
for Node.js and sys targets".

## 4. Test discovery: compile-time macro position recording (no IDE-side parsing)

`Macro.recordPositions()` runs as a build macro during the user's test compile and
records `class → { pos{file,line}, methods{name → line} }` into
`.unittest/positions.json` (`data/TestPositions.hx`). Registration differs by
framework (`Macro.setupHooks()`):

- utest: `@:autoBuild` on `utest.ITest` — hence README: discovery only works when
  `utest.ITest` is implemented / `utest.TestCase` extended.
- buddy: `@:autoBuild` on `buddy.BuddySuite`, plus runtime position capture from
  buddy's own `pos` in the patched `mapTestSpec`.
- haxe.unit: `@:autoBuild` on `haxe.unit.TestCase`.
- munit / hexUnit / tink_unittest: a **global** build macro on every class
  (`Compiler.addGlobalMetadata("", "@:build(...recordPositions(true))")`),
  filtered by a class-name regex over the whole type hierarchy — default `~/Test/`,
  overridable with `-D test-adapter-filter=<regex>` (`Macro.hx` lines 142–158;
  README "Detection of test positions").

Line numbers use `PositionTools.toLocation` and need **Haxe 4**; on Haxe 3 the
line is `null` (explicit `TODO` in `Macro.addTestPos`).

The IDE tree itself is built **from `results.json`** (`parseSuiteData`): classes
and methods that appear in the last saved results, positioned via the recorded
`pos`/`line`, grouped into package nodes by dot-path (`insertTestSuite`).
*(Inference: therefore a test that has never been run/compiled with the adapter
does not appear in the tree — there is no static source analysis and no compiler
`--display` involvement in discovery.)*

## 5. Single-test / single-class runs: filter file + full recompile, filters baked in

Mechanism (verified end to end):

1. VSCode writes the selected TestItem ids (which equal `SuiteId` /
   `SuiteId.methodName` strings) to `.unittest/filter.json` before launching the
   same `testCommand` (`HaxeTestController.setFilters`, `data/TestFilter.set`; an
   include entry starting with `"root:"` means run-all and clears includes).
2. On the next compile, `Macro.init()` **reads** `filter.json` into
   `Macro.filters`, then **deletes** the file (`Macro.hx` lines 59–64) — filters
   are one-shot.
3. The injectors embed the filter list as a compile-time constant
   (`$v{Macro.filters}`) inside the patched framework code, which skips
   non-selected tests at registration/run time via
   `TestFilter.shouldRunTest(filters, "className", "testName")` — exact match or
   prefix match `filter + "."` (`data/TestFilter.hx` lines 104–126).

Per-framework skip technique: munit — early return in `TestClassHelper.addTest`;
utest — fixtures not added in `addCase`/`addITest`; buddy — early return in
`BuddySuite.it`/`xit` (suite id includes file name so nested `describe` filtering
works — comment in `Data.hx` `SuiteId.toString`); hexUnit — descriptor lists
filtered at `run()` (this one is a runtime check of the baked-in list); haxe.unit —
filtered-out `test*` methods are *renamed* `disabled_test*` so the runner's
name-based scan misses them; tink_unittest — `runCase` returns an immediate
`Succeeded([])` future.

Consequence: **every filtered run is a full recompile** (the filter is a
compile-time constant). There are no runtime filter arguments passed to the test
executable.

## 6. Build integration: settings + `test.hxml` convention; no lime/openfl support

- Which build: purely the `haxeTestExplorer.testCommand` setting (resource-scoped,
  so per-folder in multi-root workspaces); default assumes a **`test.hxml` in the
  workspace root that both compiles and runs** the tests. Coverage runs use a
  separate `haxeTestExplorer.coverageCommand` (default `testCoverage.hxml`).
  Debugging uses a named `launch.json` configuration
  (`haxeTestExplorer.launchConfiguration`, default `"Debug"`).
- Execution: a VSCode Task with `ProcessExecution`, environment and problem
  matchers taken from the vshaxe extension's exports (`haxeExecutable.env`,
  `vshaxe.problemMatchers`) — `HaxeTestController.runHandler` lines 109–133.
  Completion is detected via `onDidEndTask` + the results-file watcher.
- **lime/openfl: nothing.** `grep -ri "lime\|openfl"` over the repo returns zero
  hits; there is no project-file handling of any kind — if a lime user wants it,
  they must hand-write an hxml-style test command themselves. Open issue #15
  ("Allow using active build task hxml for testCommand") asks for integration
  with vshaxe's active configuration; still open.
- Working directory: the adapter bakes `Sys.getCwd()` *of the compiler* into the
  generated code as the `.unittest` base folder, and the watcher pattern is
  `**/.unittest/results.json` so results in subdirectories are found too.

## 7. Limitations and caveats

Verified from README / source / issue tracker:

- **Targets**: only Node.js (`hxnodejs`) and sys targets — the result channel is
  file I/O in the test process (README line 14; `#if (sys || nodejs)` guards).
  Browser/flash targets cannot report.
- **Haxe 3**: supported (3.4.7+) but without test line numbers (README line 12;
  `TODO` in `Macro.addTestPos`).
- **Discovery gaps**: munit/hexUnit/tink_unittest positions depend on the
  `~/Test/` class-name regex (misnamed classes are invisible until customized via
  `-D test-adapter-filter`); utest discovery requires `ITest`/`TestCase`.
- **Tree = last results**: never-run tests don't show; stale entries persist until
  an unfiltered run clears `results.json`. *(inference from code, see section 4)*
- **Manual dependency**: `json2object` must be installed by the user.
- **Debugging**: `-lib test-adapter` cannot be injected into launch configs; user
  edits their hxml (README "Debugging"; open issue #23 asks how to set it up).
- **Filtered runs recompile**: filter is a compile-time constant; also filters are
  cleared after being read, so an external/manual compile between IDE runs drops
  the selection. *(second half: inference from `Macro.init` clearing the file)*
- **One run at a time**: a second run while `currentRun != null` is rejected with
  "tests already running".
- **Coverage timing**: results and LCOV files race; mitigated with a configurable
  `waitForCoverage` delay (default 2000 ms) — inherently flaky-by-timeout.
- Open issues worth knowing: #28 utest tests in abstract classes get no gutter
  icons/navigation; #2 proposes a universal framework hook API (per-framework
  patching acknowledged as fragile); #7 the adapter itself has no automated tests
  against the framework matrix.
- Compilation-server interaction: position saving is skipped when `--no-output`
  is in `Sys.args()` ("no side effects for caching, only actual builds",
  `Macro.hx` line 67).

## Takeaways for an IntelliJ implementation *(all inference)*

- The core idea transfers cleanly: ship a haxelib inside the plugin, register it
  with `haxelib dev`, append `-lib test-adapter` (or our own fork) to the user's
  test build; results/positions/filter via JSON files under a dot-folder, watched
  with a VFS listener.
- The `.unittest` JSON formats are de-facto stable (v3.0.1) and could be consumed
  as-is, giving compatibility with projects already set up for the VSCode adapter.
- IntelliJ could improve on the weakest parts: tree discovery (we have a real PSI
  index — static discovery instead of results-derived trees), lime/openfl test
  configs (we know the project model), and rerun-failed (results merge semantics
  make this easy: include-filter from failed ids).
