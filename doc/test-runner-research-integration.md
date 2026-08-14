# Test-runner feature: integration research

Research-only survey of (A) how real-world Haxe projects wire utest, and (B) the
plugin infrastructure a test-runner feature would build on. No code changes.

## Part A — reference projects

### A1. openfl/starling — lime/openfl project with utest

Sources:
- https://raw.githubusercontent.com/openfl/starling/master/tests/src/TestMain.hx
- https://raw.githubusercontent.com/openfl/starling/master/tests/project.xml

**project.xml (the test app declaration).** The tests are a full lime
application declared in `tests/project.xml`:

- `<app main="TestMain" file="StarlingTests" .../>` — the runner IS the app's
  main class; the artifact is named like any other lime app.
- `<haxelib name="openfl"/>`, `<haxelib name="utest"/>`,
  `<haxelib name="hamcrest"/>` — the test framework enters as ordinary haxelib
  dependencies of the test project file.
- `<source path="src"/>` and `<source path="../src"/>` — tests plus the library
  under test are both on the classpath; there is no separate "test scope",
  just a second project.xml living in `tests/`.
- `<window hidden="true"/>` — the windowed target still opens an application
  context (Stage/GL are needed by the display tests) but keeps it invisible, so
  the run looks headless.
- Fixture assets come in via `<assets path="fixtures" embed="false"/>`; an
  html5-only `<template>` is included with `if="html5"`, i.e. the same test app
  builds for native and browser targets.

**TestMain.hx (the runner).** `class TestMain extends Sprite` — the openfl
entry point. Its constructor:

1. `var runner = new utest.Runner()`
2. ~33 `runner.addCase(new tests.<category>.<CaseClass>())` calls —
   registration is fully manual, one line per test class, grouped by package
   (animation, display, events, filters, geometry, rendering, text, textures,
   utils).
3. `utest.ui.Report.create(runner)` then `runner.run()`.

**Results/exit in a windowed context.** There is no `Sys.exit` in TestMain:
`Report.create` prints results via trace/console, and the hidden window keeps
running after the report. Pass/fail must be read from the report output (CI
setups typically parse the output or use utest's `-D UTEST_PRINT_TESTS` /
result summary line) — the windowed app does not turn test failure into a
process exit code by itself. This matters for us: for lime-family test builds
an SM-console (or output-parsing) integration is the only reliable result
channel; the process exit code is not.

### A2. HaxeFoundation/crypto — plain hxml, one file per target

Sources:
- https://raw.githubusercontent.com/HaxeFoundation/crypto/master/tests/compile-each.hxml
- https://raw.githubusercontent.com/HaxeFoundation/crypto/master/tests/compile-hl.hxml
- https://raw.githubusercontent.com/HaxeFoundation/crypto/master/tests/src/unit/TestMain.hx
- https://api.github.com/repos/HaxeFoundation/crypto/contents/tests

**Per-target hxml layout.** `tests/` holds one `compile-<target>.hxml` per
target: `compile-cpp`, `compile-cppia(-host)`, `compile-cs(-unsafe)`,
`compile-each`, `compile-flash`, `compile-hl`, `compile-java`, `compile-js`,
`compile-jvm(-only)`, `compile-lua`, `compile-macro`, `compile-neko`,
`compile-php`, `compile-python`, plus a general `compile.hxml` and CI driver
(`RunCi.hx`/`RunCi.hxml`). The shared flags live in `compile-each.hxml`:

```hxml
-D source-header=''
--debug
-p src
-p ../src
--dce full
-lib utest:git:https://github.com/haxe-utest/utest#<pin>
-D analyzer-optimize
-D analyzer-user-var-fusion
```

and each per-target file only adds the entry point + output, e.g.
`compile-hl.hxml`:

```hxml
--main unit.TestMain
-hl bin/unit.hl
-D hl-check
```

Same shape as starling in spirit: `src` + `../src` on the classpath, utest as
a `-lib`. The "which target am I testing" question is answered by *which hxml
file you compile* — exactly the granularity of our per-build-file actions.

**TestMain shape.** `unit.TestMain.main()`:

- js-browser trace redirection into a `haxe:trace` DOM element when compiled
  for js.
- one `runner.addCase(...)` per suite (some wrapped in `#if !neko` / `#if
  !eval` conditionals for platform exclusions).
- Report configuration:

  ```haxe
  var report = Report.create(runner);
  report.displayHeader = AlwaysShowHeader;
  report.displaySuccessResults = NeverShowSuccessResults;
  ```

- **Result tracking via `runner.onProgress`**, not the report:

  ```haxe
  var success = true;
  runner.onProgress.add(function(e) {
    for (a in e.result.assertations) {
      switch a {
        case Success(pos):
        case Warning(msg):
        case Ignore(reason):
        case _: success = false;
      }
    }
    #if js
    if (js.Browser.supported && e.totals == e.done) {
      untyped js.Browser.window.success = success;
    };
    #end
  });
  ```

- **Exit-code behavior:** TestMain itself never calls `Sys.exit`; on js it
  publishes `window.success` for the CI harness to poll. (utest's own
  `UTEST_EXIT_CODE`/`Runner` complete-handler is the usual way to get a
  nonzero exit on sys targets; crypto instead lets `RunCi.hx` interpret the
  output.) Takeaway: even hxml/CLI projects cannot be assumed to exit nonzero
  on failure — parsing structured runner output is the robust channel, which
  is precisely what a wrapper TestMain emitting teamcity service messages
  would give us.

### What both projects tell the feature

- A "tests build file" is just another build file (`tests/project.xml`,
  `tests/compile-<target>.hxml`) — our per-file actions/targets model fits
  as-is; we need only a way to *mark* which file is the tests one.
- The runner main is boilerplate with one degree of freedom: the `addCase`
  list. A generated wrapper main (utest supports `utest.utils.TestBuilder` /
  `Runner.addCases(pack)` for folder-based discovery) can replace the
  hand-maintained list and add service-message reporting + exit codes.
- Exit codes are unreliable across targets (windowed lime apps never exit;
  crypto's js publishes a flag); stdout is the common denominator.

## Part B — plugin integration surface

Map: class → role → what the test feature reuses/extends.

### B1. Run configurations (compile-then-run pipeline)

| Class | Role | Test feature |
|---|---|---|
| `src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionRunConfiguration.java` | Runs one action of a build file. Stores only a reference (build file path + action name + extra args); re-resolves via `HaxeCompileCommands.resolveAction` at every launch, wraps the process in a `CommandLineState` with `KillableColoredProcessHandler` | The natural base/sibling for a `HaxeTestRunConfiguration`: same reference-not-command storage, same resolve-at-launch. The `getState()` anonymous `CommandLineState` is where a test console attaches (see below) |
| `src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeCompileCommands.java` | `resolveAction(project, buildFilePath, actionName, extraArgs)` → `Resolved(containerId, command, workDirectory, presentable, connectEligible)`; `connectIfEnabled` injects `--connect <port>` for the compilation server | Reused verbatim: a test compile is just an action resolve with extra arguments (`--main TestMainWrapper`, extra `-cp`, `-lib utest`) appended — `extraArguments` already flows through `ParametersListUtil.parse` into the command |
| `src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeProgramLaunches.java` | Maps a build's target output to the run configuration able to launch it. `specFor(target, targetOutput, type)`: HL→HashLink app, JS→Browser, SWF→Flash, CPP (lime/nmml only)→HXCPP IntelliJ. `findOrCreate` builds the config, sets the module, attaches a `HaxeActionBeforeRunTaskProvider.Task` build step, registers with `RunManager` | The compile-then-launch precedent. A test runner for sys targets (HL, CPP, neko, jvm…) follows the same shape: compile the tests build file, then launch its artifact — but with the console replaced by an SM test console. The `targetOutput`-driven dispatch is directly reusable for "how do I execute the produced test artifact" |
| `HaxeBuildFileInfo` (`v2/buildsystem/HaxeBuildFileInfo.java`) | What a build file declares: `target`, `targetOutput`, defines, libraries, classpaths (hxml carries target inline; XML projects choose target in UI) | The test feature reads `libraries` to detect utest/munit/buddy as a dependency of the marked tests file, and `targetOutput` to know what to execute |
| `NmeProjects.targetArtifact` (`v2/buildtools/NmeProjects.java:107`), `HaxeProgramLaunches.binDirBesideObj`/`configureLimeHashLink` | Tool-specific knowledge of where the runnable artifact lands (`bin/<platform>/<app>/...`, lime's `hl/bin/hlboot.dat`, obj→bin derivation) | Reused untouched — running a lime/nme test app is running its ordinary artifact |

**SM test console insertion point.** `HaxeActionRunConfiguration.getState()`
(and `HaxeProgramLaunches`-created DAP configs) currently produce a plain
colored console. The platform pattern is
`SMTestRunnerConnectionUtil.createAndAttachConsole(frameworkName, processHandler, consoleProperties)`
with an `SMTRunnerConsoleProperties`-carrying run configuration; the test
process emits teamcity service messages (`##teamcity[testStarted ...]`) which
an `OutputToGeneralTestEventsConverter` (default one parses service messages)
turns into the test tree. **Nothing in this repo uses SM test running today**
— `grep -i "SMTRunner|SMTestRunner|ServiceMessage|teamcity"` over the whole
worktree finds zero hits. This is greenfield: a new
`HaxeTestRunConfiguration` (or a test-mode state on the action config) owns
the `SMTRunnerConsoleProperties` and swaps the console; the wrapper TestMain
emits the service messages from `runner.onProgress` (the exact hook crypto's
TestMain already demonstrates).

### B2. Tool window / build-file marking

| Class | Role | Test feature |
|---|---|---|
| `v2/buildtools/settings/HaxeBuildFilesStore.java` | Per-container manual corrections to build-file auto-detection (`addedPaths`/`hiddenPaths`), persisted in `.idea/haxeBuildConfig.xml` | Model for persistence style, not the marking itself |
| `v2/buildtools/settings/HaxeActiveBuildFileStore.java` | The project-wide single active build file; `resolveActivePath` falls back to the only candidate; fires `HaxeBuildSettingsListener.TOPIC` on change | **The precedent for "mark as tests build file"**: a sibling `HaxeTestBuildFileStore` (same `@State`/`@Storage("haxeBuildConfig.xml")`, same listener topic) — likely per container rather than per project, since each module can have its own tests file. `resolveActivePath`'s single-candidate fallback maps to "a container whose only extra hxml is `tests/*.hxml` needs no marking" |
| `v2/toolwindow/HaxeToolWindowModelBuilder.java` | Read-side model: scans containers, merges detected+manual files, parses each into `FileEntry(buildFile, info, manual, actions)`, resolves `activePath`, environment, compile-command and server rows | Extend `FileEntry`/`BuildFileRow` with a `tests` flag so the tree can badge the marked file and offer test actions on it |
| `v2/toolwindow/actions/HaxeSetActiveBuildFileAction.java` | Tree context action: `panel.getSelectedUserObject() instanceof BuildFileRow` → store.setActiveFile → refresh (+ library sync) | Template for a `HaxeMarkAsTestBuildFileAction` — same selection/update/refresh shape, minus the library sync |
| `HaxeBuildSettingsListener.TOPIC` | Invalidates derived state (define context, tool window) on any build-settings mutation | Marking/unmarking a tests file fires the same topic so gutter markers and run configs re-evaluate |

### B3. Gutter icons / test detection

- **Existing line markers:** exactly one registration in
  `src/main/resources/META-INF/plugin.xml` (line 222):
  `<codeInsight.lineMarkerProvider language="Haxe" implementationClass="...ide.HaxeLineMarkerProvider"/>`
  — the inheritance (override/implement) markers. There is **no
  `RunLineMarkerContributor`** anywhere in `src/` and no `runLineMarkerContributor`
  registration; the run-gutter surface is new. The platform way: register
  `<runLineMarkerContributor language="Haxe" implementationClass="..."/>`,
  return `Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(...))`
  from the leaf identifier of a test class/method.
- **Cheap "extends/implements X" detection:**
  `src/main/java/com/intellij/plugins/haxe/lang/psi/stubs/index/specialized/HaxeClassInheritanceStubIndex.java`
  — `KEY = "haxe.class.superclass"`, keyed by the *simple name* of the
  supertype; `getBySuper(superName, project, scope)` answers "which classes
  extend/implement `Test`" from stubs, dumb-mode-safe (returns empty when
  dumb). The reverse direction a gutter contributor needs ("does THIS class
  extend utest.Test") is answered from the class's own stub-backed supertype
  list without resolving — plus, since utest cases need no base class
  (any class with `test*` methods passed to `addCase` works), detection must
  also accept "class referenced from the tests build file's main" or fall
  back to method-name convention inside classes reachable from the marked
  tests source root.

### B4. Build-context resolution (for wrapper compiles)

- `v2/display/HaxeCompilerDisplayService.contextFor(VirtualFile|Module)`
  (`HaxeCompilerDisplayService.java:118-156`): module → current build file →
  `DisplayContext`: for HXML, args are literally
  `--cwd <dir> <buildfile.hxml>`; for lime-family, a `LimeDisplaySpec`
  (directory, file, tool, selected target flag, mod stamp) that resolves via
  `lime display`; NMML goes through the last `nme prepare` evaluation. Gated
  on the per-container compilation-server opt-in; carries the container's SDK
  name and IDE define overrides.
- `v2/buildtools/HaxeDefineContextService` — the ACTIVE build file's evaluated
  defines (via `lime display` for xml projects) overlaid with IDE overrides;
  cached with a lock-free fast path; feeds parsing/indexing.

A wrapper-compile ("compile a generated TestMain against the tests build
file's context") needs exactly the `contextFor` derivation, but pointed at the
*marked tests file* instead of the module's active/compile-command file — the
mechanics (hxml = `--cwd` + file, lime = `lime display` args, plus
`--main <Wrapper>` `-cp <generated dir>` appended) are all present; only the
"which build file" parameter differs. `HaxeCompileCommands.resolveAction` with
`extraArguments` covers the launch-side equivalent.

### B5. Debugger hookup (tests with debugging)

`v2/runconfig/HaxeActionBeforeRunTaskProvider.java`:

- `Task` (persisted per run configuration) references build file + action +
  extra args + `injectDebugArguments` opt-out.
- `executeTask` resolves the command, and **under the Debug executor** appends
  `debugAdditions(project, buildFilePath)` (lines 176-192): HXML →
  `HaxeDebugAdditions.forTarget(info.target())`; lime-family → `-debug` plus
  `--haxelib=intellij-hxcpp-debug-server` for desktop CPP targets; NMML →
  `-debug` plus `--library intellij-hxcpp-debug-server`. Output streams into
  the Build tool window via `BuildViewManager`; nonzero exit fails the launch.
- The debug configs themselves (`HashLinkRunConfiguration`,
  `BrowserRunConfiguration`, `HxcppIntellijRunConfiguration`,
  `FlashRunConfiguration` — all `DapRunConfigurationBase` flavors) are what
  `HaxeProgramLaunches.findOrCreate` instantiates with that build step
  attached.

Tests-with-debugging rides this unchanged: a test run configuration whose
before-run task points at the tests build file gets a debuggable test build
for free on the Debug executor, and the artifact launches under the existing
DAP debuggers. The one open question is console ownership: the DAP configs own
their execution console, so the SM test console must be attached to the
debuggee's process handler inside (or beside) the DAP session UI.

## Gaps — what does NOT exist yet

1. **SM test console integration.** Zero uses of
   `SMTestRunnerConnectionUtil`/`SMTRunnerConsoleProperties`/teamcity service
   messages anywhere in the repo. Everything runs in a plain
   `KillableColoredProcessHandler` console or the Build view.
2. **Test detection.** No test framework model at all: no detection of
   utest/munit/buddy in a build file's `-lib`s, no "is this class a test case"
   predicate, no index of test classes/methods.
   `HaxeClassInheritanceStubIndex` exists but nothing test-shaped consumes it.
3. **Run gutter markers.** No `RunLineMarkerContributor` (or any
   `runLineMarkerContributor` registration); the only line marker is the
   inheritance provider.
4. **Tests-build-file marking.** No store, tree flag, or context action for
   designating a build file as the tests build; `HaxeActiveBuildFileStore` and
   `HaxeSetActiveBuildFileAction` are precedents only.
5. **Wrapper generation.** Nothing generates a runner main (TestMain wrapper
   with service-message reporting / exit-code handling), and nothing compiles
   a synthetic main against a build file's context — `contextFor` +
   `resolveAction(extraArguments)` provide the ingredients but no consumer
   exists.
6. **Test run configuration type.** No test-flavored run configuration,
   locator (`SMTestLocator` for click-through from the test tree to Haxe
   PSI), or rerun-failed-tests action.
