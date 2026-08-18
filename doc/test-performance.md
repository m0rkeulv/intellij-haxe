# Test-suite performance profile — 2026-08-18

Profiled the full root suite (1361 tests) with JFR recorded INSIDE the forked
test JVM (`-PtestJfr=true`, `settings=profile`, dump on exit). Three clean
recordings with near-identical profiles (hot frames within a few percent,
error counts within 0.2 %); the deep analysis below is from
`hotspot-pid-48116-…_00_13_30.jfr` (399 s wall, 81 975 samples), re-printed
with `--stack-depth 64` — **`jfr print` defaults to 5-frame stacks**, which
silently cripples attribution (an earlier pass concluded "nothing in plugin
logic" from exactly that artifact).

Why not the IDE profiler: profiling the gradle task from the IDE attaches
async-profiler to a short-lived gradle WORKER JVM, not the test fork — the
provided snapshot covered 0.5 s of a process with 1.8 s total CPU. The
`-PtestJfr` property is the reliable route.

## Where the wall time goes — measured, not inferred

`jdk.ThreadSleep` gives exact waiting time. **The EDT (which runs every
test) slept 197.5 s of the 399 s run — half the suite is polling waits:**

| wait | total | what it is |
|---|---|---|
| `IndexingTestUtil.waitUntilIndexesAreReady` under `HaxeCodeInsightFixtureTestCase.setUp` | **138 s** (13 808 polls × 10 ms) | every heavy-fixture test opens a fresh project and waits for it to be indexed — ~120 ms per test |
| `TestDaemonCodeAnalyzerImpl.doRunPasses` + annotator `doTest` waits | ~37 s | EDT waiting for background highlighting to finish (semantic annotator suites dominate) |
| `HaxeRecursiveStdInferenceTest.testArraySortHighlightingTerminates` | 10 s | ONE test: the termination guard genuinely spends ~10 s highlighting std `ArraySort.hx` — the resolver-CPU issue below, concentrated |
| `MockApplication.setUp` | 9.5 s (190 × 50 ms) | platform-internal |

Per-testcase distribution (unchanged across runs): median 326 ms, p90
456 ms, 217 tests under 50 ms. The ~300 ms heavy-fixture floor ≈ 85 % of
wall time; the 138 s index wait above is its single biggest measured part.

## Where the CPU goes (per-thread, 64-frame stacks)

| thread group | share of samples | dominant content |
|---|---|---|
| coroutine `DefaultDispatcher-worker-N` | 73.7 % | the scheduler's work-steal SPIN (`WorkQueue.stealWithExclusiveMode` 62 % of ALL samples) — idle-core burn, not wall time |
| `ForkJoinPool` (the real-work pool) | 18.2 % | **62 % haxe resolver/model** (below), 10 % checkCanceled/locks, 8 % indexing |
| `AWT-EventQueue-0` (runs the tests) | 6.9 % | test bodies 27 %, tearDown 13 %, highlighting 12 %, setUp 7 %, command execution 5 %, kover 4 %, rendering 0.1 % |
| app pool / main / other | 1.2 % | indexing, kover class-instrumentation on `main` |

`Test worker` has zero samples — it only waits on the EDT.

### The resolver dominates real CPU

Top plugin frames in the ForkJoinPool (parallel highlighting) — and the
method list confirms `HaxeEvaluationTaint.computeOrTaint` on top with 90 %
of its backtraces through `RecursionManager.computePreventingRecursion`:

| frame | share of FJP |
|---|---|
| `HaxeProcessDeclarationsHelper.getDeclarationElementToProcess` | 5.2 % |
| `HaxeExpressionEvaluator.referenceSearch` | 4.5 % |
| `AnnotatorUtil.shouldSkip` | 3.7 % |
| `UsefulPsiTreeUtil.getChildrenOfType` | 3.6 % |
| `AnnotatorUtil.isInGeneratedPreview` | 3.3 % |
| `HaxeResolverScopeProcessor.execute`, `checkIsTypeParameter`, `HaxeClassModel.isValid`, `SpecificHaxeClassReference.getHaxeClass` | ~3 % each |

This CPU sits behind the ~37 s of daemon waits above (the EDT waits for
exactly this work), so it IS on the critical path of the annotator suites.
It is the same machinery task #37 (stamp-based failure caching +
call-expression phases) targets.

## Smaller findings

- **Kover instruments every run**: `kover-jvm-agent-0.9.8` (plus the
  IntelliJ coroutines agent) is attached by the build. Visible steady-state
  CPU ~0.4 % plus class-load instrumentation early on `main`; per-hit
  recording is inlined and invisible to sampling. Worth a one-off A/B with
  the kover plugin disabled to bound the real cost.
- **`jdk.ProcessStart` (30)**: one `wsl.exe --list` (platform env
  detection); the rest is `HaxeTestRunnerPipelineTest` launching real
  toolchains. The availability probes re-run per test — `haxe --version`
  8×, `haxelib path <lib>` 6×, `neko -version` 2× in six seconds, each a
  process spawn awaited on the EDT. `toolAvailable` results cannot change
  mid-run; a static cache saves ~1–2 s.
- **Monitor contention: none that matters.** `ReferenceQueue$Lock` waits
  are idle reference-handler threads; `RunSuspend` 16.7 s across 905 waits
  is write-lock suspension bookkeeping; `java.util.Vector` 10 s over 9
  waits is process `waitFor`.
- **`jdk.DeprecatedInvocation` (1 195)**: all library-level
  (`sun.misc.Unsafe` from bundled libs, `AccessController`) — nothing ours.
- **`com.intellij.platform.CheckCanceledEvent` (60 311)**: all ~0 ms — the
  platform's own slow-checkCanceled detector found nothing slow.
- GC: 3.3 s total across 908 pauses (0.9 % of wall) — fine.

## The NoSuchMethodError storm

~570 k `java.lang.NoSuchMethodError` throws per run (≈1 500/s), stable
within 0.2 % across four runs — tied to fixed per-test work, not noise.

RESOLVED via the event's `message` field (the stacks are a dead end: the
throw happens during method-handle LINKAGE, where JFR cannot walk the stack,
and `-XX:+ShowHiddenFrames` does not help — tried; that flag also breaks
stack-sensitive platform code wholesale, 954 of 1361 tests failed under it,
so never run the suite with it).

The messages show 570 251 of 570 943 (99.9 %) are CONSTRUCTOR probes:
`void <init>(Project)` / `<init>(CoroutineScope)` /
`<init>(Project, CoroutineScope)` over every project-level service class.
The service container instantiates a service by TRYING constructor
signatures in order via method handles, catching the NoSuchMethodError per
miss until one binds. Counts are exact multiples of project opens (903 per
service), covering platform services, bundled IU plugins (kubernetes,
rdserver — instantiated on every test project open), and two of ours with
no-arg constructors (`HaxeUntypedParameterBindingCache`,
`HaxeExpressionEvaluatorCacheService`, one missed `(Project)` probe each per
open). Not a version/SDK problem — the intended discovery protocol, merely
amplified by per-test project creation. Optional trims, both cosmetic for
CPU but the second also shaves fixture cost: give the two haxe services a
`(Project)` constructor; check whether the test platform can load fewer
bundled plugins so each project open instantiates fewer services.

## Levers, ranked

1. **Per-test fixture cost — now with a hard number: 138 s of the run is
   waiting for per-test project indexing alone.** The platform's answer is
   the LIGHT fixture (`LightJavaCodeInsightFixtureTestCase` family): one
   shared project reused across tests, reopened only when the project
   descriptor changes — which removes the per-test index wait AND the
   open/dispose cost. A pilot migration of one big suite (e.g. semantic
   annotator) would measure the real win; extrapolated, cutting the floor
   to ~50 ms roughly HALVES the suite. Non-trivial; sequencing after the
   V1 removal (#31) makes sense.
2. **Resolver/evaluator CPU (task #37).** 62 % of the real-work pool and
   the ~37 s of daemon waits sit in `computeOrTaint`/resolve machinery,
   90 % of it through `RecursionManager.computePreventingRecursion`; the
   ArraySort termination test alone pays 10 s. The planned stamp-based
   failure caching + call-expression phases attack exactly this.
3. **Small, cheap:** static-cache the tool-availability probes in
   `HaxeCodeInsightFixtureTestCase.toolAvailable` (~1–2 s); A/B a run with
   kover disabled to bound the coverage-agent cost.
4. **Coroutine scheduler spin: leave it alone.** A/B tried capping the
   scheduler (`kotlinx.coroutines.scheduler.{core,max}.pool.size=4`): all
   tests passed but the platform's coroutine-based SHUTDOWN wedged and the
   run hung until gradle's 15-minute timeout. Idle-core burn, not wall
   time; the knob was removed — do not reintroduce it.

## Repro

```
gradlew :cleanTest :test -PtestJfr=true    # record the fork's JFR
jfr print --stack-depth 64 --events jdk.ExecutionSample <file>   # NEVER default depth
```

Command-line `-D` reaches only the gradle JVM, never the fork — hence the
gradle property. Remember `--no-build-cache` when re-running with unchanged
inputs, or gradle replays cached results in seconds.
