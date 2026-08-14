# Haxe test frameworks — research for IntelliJ test-runner integration

Research date: 2026-08-12. Popularity signals from lib.haxe.org download counters and
GitHub repo metadata (via the GitHub API). Relative release dates ("published N years
ago") come from haxelib pages and are approximate to the year.

## Popularity ranking

| # | Framework | Haxelib downloads | Latest haxelib release | Maintained? | GitHub |
|---|-----------|------------------:|------------------------|-------------|--------|
| 1 | **utest** | 682,714 | 1.13.2 (~2021) | **Yes** — repo pushed 2026-03-02 | [haxe-utest/utest](https://github.com/haxe-utest/utest), 108 stars |
| 2 | **munit** (MassiveUnit) | 286,212 | 2.3.5 (~2020) | Maintenance mode — last push 2023-05 (massiveinteractive); an [openfl/munit](https://github.com/openfl/munit) fork exists | [massiveinteractive/MassiveUnit](https://github.com/massiveinteractive/MassiveUnit), 182 stars |
| 3 | **buddy** | 267,593 | 2.13.0 (~2021) | Dormant — last push 2021-03 | [ciscoheat/buddy](https://github.com/ciscoheat/buddy), 95 stars |
| 4 | **tink_unittest** | 48,531 | 0.8.0 (~2021) | Low activity — last push 2024-07 | [haxetink/tink_unittest](https://github.com/haxetink/tink_unittest), 17 stars |
| 5 | haxe-doctest | 11,810 | 3.3.0 | Niche; generates tests that RUN on utest/munit/haxe.unit/tink_testrunner — not a runner itself | [vegardit/haxe-doctest](https://github.com/vegardit/haxe-doctest) |
| 6 | **hexunit** | 4,329 | 1.0.0-alpha.7 (~2019) | **Dead** — last push 2019-08, never left alpha | [DoclerLabs/hexUnit](https://github.com/DoclerLabs/hexUnit), 9 stars |
| 7 | **haxe.unit** (old std) | n/a (was in std) | — | Removed from std in Haxe 4; survives only via [hx3compat](https://github.com/HaxeFoundation/hx3compat) | — |

Sources: [utest tag page](https://lib.haxe.org/t/unittesting/) (682,714 dl),
[testing tag page](https://lib.haxe.org/t/testing/) (munit/buddy/haxe-doctest dl),
haxelib search for [hexunit](https://lib.haxe.org/search?v=hexunit) and
[tink_unittest](https://lib.haxe.org/search?v=tink_unittest).

Other libs on the testing tag (hxtf 30 dl, nodeunit 317 dl, haxeium 9 dl) have no
real adoption and are not worth supporting.

## vshaxe test ecosystem (reference point)

The VS Code [haxe-test-adapter](https://github.com/vshaxe/haxe-test-adapter) supports
exactly six frameworks: **munit, utest, buddy, hexUnit, tink_unittest, haxe.unit**.
Mechanism (from its [README](https://raw.githubusercontent.com/vshaxe/haxe-test-adapter/master/README.md)):
the `test-adapter` haxelib (7,699 dl) is added to the build (`-lib test-adapter`,
needs `json2object`); macros hook each framework and record results as JSON into a
`.unittest/` folder in the workspace, plus `positions.json` for source locations.
For munit/hexUnit/tink_unittest, test-position detection relies on a class-name
filter defaulting to `~/Test/` (customizable via `-D test-adapter-filter=<filter>`);
for utest it requires `utest.ITest` implemented / `utest.TestCase` extended.
Line-number detection needs Haxe 4+; adapter works only for Node.js and sys targets.
This library is an alternative integration strategy: one hook covering all six
frameworks, at the cost of post-hoc JSON files instead of live streaming output.

---

## 1. utest (primary target)

Repo: https://github.com/haxe-utest/utest (originally fponticelli/utest; haxelib
package `utest`). Requires **Haxe 4.1+** on master (`#error` below 4.1.0).
Haxelib still serves 1.13.2 while the repo is actively developed — expect users on
1.13.x.

### (a) Test class declaration

- A test class **extends `utest.Test`** or **implements `utest.ITest`** (the
  interface exists so a class can keep another base class; `utest.Test` is the
  convenience base). A macro build step collects test methods.
- Test methods are found **by name prefix**: `test*` (normal tests) and `spec*`
  ("spec" methods where boolean binops are implicitly asserted).
- Lifecycle: `setup()`/`teardown()` per test, `setupClass()`/`teardownClass()` per
  class — all may take an `Async` argument for async setup.
- Metadata: `@:timeout(ms)` (default async timeout 250 ms), `@:ignore("reason")`,
  `@:depends(otherTest)` (dependency ordering; `-D UTEST_IGNORE_DEPENDS` bypasses).
- Legacy note: pre-ITest versions used reflection over any class added to the
  Runner (the vshaxe adapter also names `utest.TestCase`); gutter detection should
  key on `utest.Test` / `utest.ITest`.

### (b) Invocation

User writes a `TestMain`-style entry point. Short form:

```haxe
utest.UTest.run([new TestCase1(), new TestCase2()]);
```

Long form (what `UTest.run` does internally):

```haxe
var runner = new utest.Runner();
runner.addCase(new TestCase1());     // addCase(test:ITest, ?pattern:EReg)
runner.addCases(my.pack);            // macro: adds every test class in a package
                                     //   (recursive=true, nameFilterRegExp='.*')
utest.ui.Report.create(runner);
runner.run();
```

`Runner` exposes dispatcher events usable by a custom reporter: `onStart`,
`onProgress` (`{result, done, totals}`), `onTestStart`, `onTestComplete`,
`onPrecheck`, `onComplete`.

### (c) Output / reporting — the key integration fact

`utest.ui.Report.create(runner)` picks a platform reporter, and the selection is
compile-time:

```haxe
#if teamcity
    report = new utest.ui.text.TeamcityReport(runner);
#elseif travis
    ...PrintReport...
```

- **`-D teamcity` activates a built-in TeamCity service-message reporter**
  (`utest.ui.text.TeamcityReport`) emitting `##teamcity[...]` messages:
  `testSuiteStarted`/`testSuiteFinished` (per package and class),
  `testStarted`/`testFinished`, `testIgnored` (with reason), `testFailed` (message
  + details). Root suite name defaults to `Target: <name>` and is overridable with
  `-D teamcity_suite_name=...`. IntelliJ's `ServiceMessageParser`/SMTRunner can
  consume this almost directly — this is the cheapest possible integration: add
  `-D teamcity` to the test compile and parse stdout.
- Default reporter is `PrintReport` (console) or `HtmlReport` (browser JS/Flash/PHP
  web). Display tuning: `Report.create(runner, successDisplayMode, headerDisplayMode)`
  with `NeverShowSuccessResults`/`ShowSuccessResultsWithNoErrors` and
  `AlwaysShowHeader`/`ShowHeaderWithResults`.
- Plain-text summary format: header with assertion/success/failure/error/warning
  counts + time, `ALL TESTS OK` / `SOME TESTS FAILURES` verdict, per-test status
  lines (`OK`, `ERROR`, `FAILURE`, `WARNING`) with per-assertion symbols.
- **Exit code**: the text reports call, on completion,
  `Sys.exit(result.stats.isOk ? 0 : 1)` on sys targets
  (php/neko/cpp/cs/java/python/lua/eval/hl) and `process.exit(code)` /
  `phantom.exit(code)` on JS. Unconditional on those targets — there is **no
  `UTEST_EXIT` define** in current source (contrary to folklore; verified in
  `src/utest/ui/text/PlainTextReport.hx`). So a green/red exit code is free.
- `-D UTEST_FAILURE_THROW`: failures throw instead of accumulating (turns any
  failure into a crash — not useful for the IDE runner).

### (d) Filtering

- **`-D UTEST_PATTERN=<pattern>`** (also read from the `UTEST_PATTERN` environment
  variable at compile time): plain string or regex without delimiters, matched
  against `ClassName.methodName`, sets `Runner.globalPattern` and overrides
  per-case patterns. This is compile-time, so running a single test means a
  recompile with the define — acceptable for a Haxe workflow where every run is a
  compile anyway.
- Per-case: `runner.addCase(case, ~/pattern/)`.
- `-D UTEST_PRINT_TESTS` prints each test name as it executes.

### Integration verdict

**Easy.** `-D teamcity` + exit code + `UTEST_PATTERN` covers run, report, and
filter with zero custom code on the Haxe side. Gutter detection: class implements
`utest.ITest` (directly or via `utest.Test`), methods prefixed `test`/`spec`.

## 2. munit (MassiveUnit)

Repo: https://github.com/massiveinteractive/MassiveUnit (fork:
https://github.com/openfl/munit). Deps: mlib, mcover.

- **(a) Declaration**: metadata-driven, **no base class**: `@Test` methods,
  `@AsyncTest` (async, takes a factory), `@Before`/`@After`,
  `@BeforeClass`/`@AfterClass`, `@Ignore`. Assertions via `massive.munit.Assert`.
  Test classes are discovered by the command-line tool scanning the test source
  dir; a `TestSuite` class is **generated** (`haxelib run munit gen`).
- **(b) Invocation**: driven by the haxelib tool: `haxelib run munit test [targets]`
  (config in `.munit` file; targets js/swf/cpp/java/cs/python/php/neko; Node via
  `-lib hxnodejs`/`-D nodejs`). Programmatic runner: `TestRunner` with a client,
  e.g. `RichPrintClient` (HTML for js/flash), `PrintClient` (console).
- **(c) Output**: **JUnit-style XML reports written to the filesystem** via
  `JUnitReportClient`; `haxelib run munit report teamcity` converts results for
  TeamCity; `-result-exit-code` makes the tool exit non-zero on failure. So both
  JUnit XML and (converted) TeamCity output exist — good machine-readable options.
- **(d) Filtering**: no documented CLI flag to run a single test class/method
  (unverified beyond README/docs search — the tool runs the generated suite as a
  whole; filtering in practice means regenerating/editing the suite). The vshaxe
  adapter also does its own recording rather than using a munit filter.

**Integration verdict: Moderate.** Reporting is solved (JUnit XML or the teamcity
conversion), but the tool-orchestrated run (`haxelib run munit test`, browser
launching for js/swf) is heavier to wrap, and single-test filtering has no
first-class hook. Gutter detection must be metadata-based (`@Test` etc.), not
base-class-based.

## 3. buddy

Repo: https://github.com/ciscoheat/buddy (BDD-style). Deps: asynctools, promhx.

- **(a) Declaration**: suites **extend `buddy.BuddySuite`** (or
  `buddy.SingleSuite`); tests are `describe("...", { ... it("should ...", ...) })`
  closures — **test identities are strings, not methods**, so gutter icons can
  only be mapped to `describe`/`it` call expressions, and IDE-name↔source mapping
  is by string literal.
- **(b) Invocation**: macro-generated main — the entry class
  `implements Buddy<[Suite1, Suite2]>` (or extends `SingleSuite`); no hand-written
  `main()`. Built-in async (`done` callback, default timeout 5000 ms via
  `timeoutMs`; async unsupported on PHP/interp per README).
- **(c) Output**: console dot-format (`..PP`), summary
  `"N specs, M failures, K pending"`; **process exit code 0/1**. **Pluggable
  reporters**: implement `buddy.reporting.Reporter`, select via
  `@reporter("path.to.Reporter")` on the main class or `-D reporter=path.to.Reporter`;
  built-ins include `ConsoleReporter`, `ConsoleFileReporter`, `TraceReporter`, and
  an XUnit2-style reporter. A TeamCity reporter would be ~one small Haxe class
  shipped by the plugin and injected with `-D reporter=`.
- **(d) Filtering**: `@include`/`@exclude` **metadata on suites/specs** —
  compile-time source edits, no CLI/define to run one test by name. Pending specs =
  `it` without a body.

**Integration verdict: Moderate.** Exit code and reporter injection (`-D reporter=`)
are clean; the blockers are string-named tests (weak PSI mapping for gutters and
"run this test") and no non-invasive single-test filter. Project is dormant
(2021) but still widely used.

## 4. tink_unittest (+ tink_testrunner)

Repos: https://github.com/haxetink/tink_unittest,
https://github.com/haxetink/tink_testrunner. Docs:
https://haxetink.github.io/tink_unittest (SPA — content not fetchable; details
below verified from the repo's `tests/RunTests.hx`).

- **(a) Declaration**: plain classes, public methods are tests; annotations:
  `@:describe("name")` (naming/nesting), `@:asserts` (injects a multi-assertion
  buffer; method returns `Assertions`), `@:before`/`@:after` (around each test),
  `@:setup`/`@:teardown`, `@:variant(...)` (parameterized tests), `@:timeout(ms)`,
  `@:include`/`@:exclude`, `@:async` (with tink_await), `@:benchmark`.
- **(b) Invocation**: hand-written main:
  `Runner.run(TestBatch.make([new MyTest(), ...]))` (from `tink.testrunner.Runner`
  / `tink.unit.TestBatch`); returns a Future of the batch result. The common
  pattern `.handle(Runner.exit)` to set the process exit code is **unverified**
  (README/docs too thin to confirm; result does expose
  `result.summary().failures`).
- **(c) Output**: `tink.testrunner.Reporter` interface with a default
  `BasicReporter` (ANSI console); custom reporters are supported by passing one to
  `Runner.run` — again machine output would be a small custom reporter class.
- **(d) Filtering**: `@:include`/`@:exclude` metadata (compile-time). No CLI/define
  filter found (unverified — docs unfetchable).

**Integration verdict: Moderate-to-hard for a small audience.** Everything goes
through macros and tink futures; ~7% of utest's downloads. Support only if the
vshaxe-adapter route is chosen (which covers it for free).

## 5. hexunit

Repo: https://github.com/DoclerLabs/hexUnit (requires hexCore).

- **(a)** Annotation-based, no inheritance: `@Test("desc")`, `@Async`, `@Before`,
  `@After`, `@BeforeClass`, `@AfterClass`, `@Ignore`; suite classes carry `@Suite`
  with an array of test classes.
- **(b)** `var emu = new ExMachinaUnitCore(); emu.addTest(TestClass); emu.run();`
- **(c)** Event/listener architecture (`emu.addListener(...)`) with
  `ConsoleNotifier` (Node/CI) and `BrowserUnitTestNotifier`; no documented exit
  code or machine format.
- **(d)** `addTestMethod()` runs a single named test method (their "IDE
  integration" hook).

**Integration verdict: Skip.** Dead since 2019, alpha version, 4.3k downloads,
9 stars.

## 6. haxe.unit (old standard library)

- Haxe 3 std API: class **extends `haxe.unit.TestCase`**, methods named `test*`,
  `assertEquals`/`assertTrue`/`assertFalse`; runner:
  `var r = new haxe.unit.TestRunner(); r.add(new MyCase()); r.run();` (`run()`
  returns success as Bool; plain-text output; no reporters, no filtering, no exit
  code unless the user calls `Sys.exit` themselves).
- **Removed from the standard library in Haxe 4**
  ([breaking-changes list](https://github.com/HaxeFoundation/haxe/wiki/Breaking-changes-in-Haxe-4.0.0));
  available only via [hx3compat](https://github.com/HaxeFoundation/hx3compat)
  (`haxe.org/manual/std-hx3compat.html`).

**Integration verdict: Skip** for a plugin targeting modern Haxe; at most detect
`haxe.unit.TestCase` subclasses for gutter icons without run support.

---

## Recommendation summary

1. **utest first-class**: gutter on `utest.ITest`/`utest.Test` + `test|spec`
   prefix; run = compile with `-D teamcity` (+ `-D UTEST_PATTERN=<Class.method>`
   for single-test) and pipe stdout to the SMTRunner console; exit code is
   already 0/1.
2. **munit second**: `@Test`-metadata gutters; run via `haxelib run munit test
   -result-exit-code`; ingest its JUnit XML or the `report teamcity` conversion.
3. **buddy/tink_unittest**: pluggable-reporter frameworks — feasible via a
   plugin-shipped TeamCity reporter (`-D reporter=` for buddy; a
   `tink.testrunner.Reporter` for tink) but defer; or cover all of them at once by
   reusing the vshaxe `test-adapter` haxelib (JSON results in `.unittest/`).
4. **hexunit, haxe.unit**: not worth run support.
