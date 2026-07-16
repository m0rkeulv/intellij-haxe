# intellij-hxcpp-debugger — implementation plan

Our own HXCPP debug server: Haxe code compiled INTO the debuggee (via the
`intellij-hxcpp-debug-server` haxelib), speaking **native DAP** over TCP to the
IDE. Successor to the idea recorded in
`../../vshaxe-hxcpp-debugger-adapter/docs/future-native-dap-server.md`; the
vshaxe adapter stays fully supported alongside.

## Signed-off decisions (2026-07-15)

1. **Write fresh, port selectively.** No fork of vshaxe's Server.hx: our
   architecture is test-first (interpreter-run unit tests need the runtime
   behind an abstraction, which the fork lacks). Port proven *ideas*
   (threading model, value printing) where they earn it; the vshaxe code (MIT)
   is reference material.
2. **Separate run configuration** in the IDE ("HXCPP Application (IntelliJ)"),
   next to the vshaxe one — not wire auto-detection, not a dropdown.
3. **Exceptions: spike first, scope after.** An early milestone probes what
   the hxcpp runtime actually surfaces (uncaught, critical errors, per-throw
   hooks?); all/uncaught/typed filters are scoped from evidence.
4. **Connection via env vars + IDE-chosen ephemeral port.** The server reads
   `HXCPP_DEBUG_HOST`/`HXCPP_DEBUG_PORT` env vars at startup (fallback:
   compile-time defines, then 127.0.0.1:6972) and **connects out** to the IDE,
   which listens on a free port per session. No rebuild to change ports; the
   port-collision bug class is gone.

Standing rules carried over from the HashLink work: never crash/freeze the
debuggee — degrade to missing values or a clear refusal; every milestone lands
with unit tests (interpreter) and, once fixtures exist, integration tests
(real compiled exe); mechanical robustness over per-version special cases.

## Why in-process changes everything (vs the HashLink adapter)

The server runs INSIDE the debuggee, so the hard 80% of the HashLink work
disappears: no memory layout (`Align`), no INT3 patching, no trampolines or
calling conventions, no stack walking. `cpp.vm.Debugger` (verified in std)
gives us: thread infos with frames, file/line + class/function breakpoints,
`breakNow`, `continueThreads`, `stepThread` (into/over/out), and stack
variable get/**set with a real frame number** and `value:Dynamic` — so writes
to any frame and real object construction are ordinary Haxe. Evaluation can
use actual reflection and direct calls instead of injected machine code.

What stays hard: the runtime's event model and threading (a debugger thread
inside a stopped world), expression evaluation, and DAP bookkeeping.

## Module layout

```
debuggers/intellij-hxcpp-debugger/        (gradle: :debuggers:intellij-hxcpp-debugger)
  haxelib/                                the published library root
    haxelib.json                          name: intellij-hxcpp-debug-server (MIT)
    extraParams.hxml                      --macro injecting the server boot when -debug
    intellij/hxcpp/debug/...              server sources
  src/test/haxe/                          interpreter-run unit tests (TestMain, Assert, fakes)
  src/test/java/                          DAP integration tests (reuse :dap-protocol DapClient)
  test-fixtures/                          debuggee programs compiled with the haxelib
  docs/                                   this plan, README (gotchas as they are learned)
  build.gradle.kts                        fixture builds + test wiring (mirrors the vshaxe module)
  test.hxml                               unit tests under the interpreter
```

Local development uses `haxelib dev intellij-hxcpp-debug-server <repo>/debuggers/intellij-hxcpp-debugger/haxelib`
(a gradle task sets this up for fixture builds; users of a release would
`haxelib install`).

**Testability keystone** (the reason for writing fresh): all `cpp.vm.Debugger`
access goes through a `DebuggerApi` interface. The real implementation is
cpp-only; a scriptable fake drives every unit test under the interpreter —
same pattern as the HashLink adapter's `DebugApi`/`FakeDebugApi`, which is
what made 392 unit checks possible there.

## Milestones

- **M0 — scaffolding.** Gradle module, haxelib skeleton + dev wiring, unit-test
  harness (TestMain/Assert), boot macro that starts the server only when
  compiled with `-debug` and the lib. Exit: `haxe test.hxml` runs a trivial
  test; a fixture compiles with the lib and starts (server connects nowhere
  gracefully when no env/define is set).
- **M1 — wire core.** DAP Content-Length framing + JSON envelope (server
  side), connect-out transport from env vars, request dispatch skeleton:
  initialize (capabilities), configurationDone, disconnect, threads. The
  debugger thread + event-notification handler with a `DebuggerApi`
  abstraction; stopped/continued/exited events. Unit tests: framing, dispatch,
  event ordering over fakes.
- **M2 — breakpoints.** setBreakpoints with suffix-based file matching (fixes
  vshaxe gotcha #4: exact-full-path only), verified results + breakpoint
  events, stopped(reason:"breakpoint"). Conditions parked until M5 (they need
  eval).
- **M3 — run control.** continue, pause (`breakNow`), step in/over/out
  (`stepThread`), stackTrace with source mapping, thread lifecycle events.
  Step policy: surface stops only when the line changes (fixes vshaxe gotcha
  #6, same-line expression re-hits).
- **M4 — variables.** scopes/variables from frame + stack-variable APIs,
  structured expansion via in-process reflection (arrays, objects, maps,
  enums, anon), per-stop variablesReference registry, **setVariable to any
  frame** (the marquee fix over vshaxe; includes string/object writes).
- **M5 — evaluate.** Expression parser/interpreter (fresh; concepts may be
  adapted from the HL adapter's pure-Haxe ExprParser — explicit decision
  point here, default is no code sharing), evaluate for watches/hover,
  assignment via evaluate, conditional breakpoints.
- **M6 — exception spike, then implementation.** Probe the runtime: what do
  stopped events deliver for uncaught exceptions and critical errors; is
  there any per-throw hook for "all exceptions"? Then implement the supported
  subset (uncaught + critical errors expected; typed filters if the thrown
  value is reachable) and DOCUMENT what is not supportable and why.
- **M7 — IDE integration.** New run configuration "HXCPP Application
  (IntelliJ)" with the polish from the HashLink config (no parenthetical
  hints, browse-at-current-path, custom-binary-style overrides where they
  make sense), runner that listens on an ephemeral port + sets the env vars,
  a DapClient-based debug process (the :dap-protocol module unchanged),
  breakpoint/exception-breakpoint wiring, smart step into only if M3 grew
  stepInTargets (see backlog).

  *Delivered (2026-07-16):* the vshaxe M4 debug process was parameterized
  over an `HxcppDapBackend` (connect strategy + launch/exception-filter
  capabilities) instead of forking it, so both debuggers share one DAP
  client, breakpoint manager and frame/value machinery. New
  `hxcpp.intellij` package: `HxcppIntellijBackend` (ephemeral loopback
  listener bound BEFORE spawn; debuggee guided in by HXCPP_DEBUG_HOST/PORT
  env vars — no port setting, no concurrent-session collisions),
  run configuration + editor (module/executable/workdir/args only,
  browse-at-current-path, hints as help text not parentheticals), Run and
  Debug runners, and "HXCPP Uncaught Exceptions"/"HXCPP Critical Errors"
  breakpoint types (both default-ON, matching the server) driving the
  uncaught/critical filters in-phase before configurationDone. Plain Run
  sets no env vars: the embedded server sees an unconfigured session and
  stays out of the way. IDE-side smoke testing happens in M8 alongside the
  integration suite.
- **M8 — integration suite + hardening.** Fixture matrix and gradle tasks
  mirroring the vshaxe module (toolchain probing, graceful skips), the full
  test parity list from the HashLink suite where applicable, README gotchas
  document.

Each milestone: sign-off before the next; unit + (from M8 backfilled to M2)
integration tests green before commit.

## Parked / backlog

- Smart step into: FEASIBLE, mechanism researched and recorded in
  `smart-step-into-research.md` (IDE-side PSI target discovery + a temporary
  class/function breakpoint under a STEP_OVER — the runtime evaluates both
  together, verified in hxcpp 4.3.2). Schedule after M3 + M7.
- Attach mode (connect to an already-running debuggee): deferred, launch-only
  v1 (same decision as the vshaxe adapter).
- Upstream PRs to vshaxe (env-var port, frame-number writes, suffix matching)
  — still worthwhile, independent of this module.
