# hxcpp APIs we can use instead of building (per-milestone survey, 2026-07-15)

Sources verified locally: `std/cpp/vm/Debugger.hx` (Haxe API + structures),
hxcpp 4.3.2 `src/hx/Debugger.cpp` / `Debug.cpp` / `include/hx/StackContext.h`
(runtime), vshaxe hxcpp-debug-server 1.2.4 (MIT reference). Net effect on the
plan: M3 and M4 shrink substantially; M6's spike already has its central
answer.

## M0 — scaffolding

- **Boot mechanism is a 5-line macro** (vshaxe `Macro.hx`): when
  `cpp && debug`, `Context.getType(<server class>)` forces inclusion and
  `Compiler.define("HXCPP_DEBUGGER")` turns on the runtime's debugger support
  (checked throws, instrumentation hooks). The server then starts from its own
  `__init__`/static ctor. Copy this pattern verbatim; `HXCPP_DEBUGGER` is the
  define that matters, `-debug` supplies the stack/line instrumentation.
- `Macro.getDefinedValue(key, default)` — the define-fallback trick for our
  env-var-first config chain.

## M1 — wire core

- `Debugger.enableCurrentThreadDebugging(false)` on the server thread — the
  runtime additionally hard-excludes the registered debug thread from breaking
  (`Debugger.cpp:564`), so the server can never stop itself.
- `setEventNotificationHandler(handler)` delivers THREAD_CREATED/TERMINATED/
  STARTED/STOPPED **with the stop position** (threadNumber, stackFrame,
  className, functionName, fileName, lineNumber). CAUTION (from the std docs /
  vshaxe usage): the handler runs on the *stopping* thread — hand off to the
  server thread through a queue, never do protocol I/O in it.
- Sockets/threads are plain `sys.net.Socket` + `sys.thread` — nothing to build.

## M2 — breakpoints

- `addFileLineBreakpoint(file, line)` / `addClassFunctionBreakpoint(class, fn)`
  / `deleteBreakpoint(number|null=all)` — the engine is complete, thread-safe
  (copy-on-write breakpoint lists with memory barriers), including a
  quick-reject hash so idle breakpoints are nearly free.
- **`getFilesFullPath()` + `getFiles()`** give the runtime's own file tables —
  our suffix-matching (vshaxe gotcha #4 fix) is a pure-Haxe map over these,
  and the two arrays are parallel (index-aligned), so IDE path → runtime file
  key is one lookup.
- No RUNTIME line table exists: the generated `HXLINE(n)` markers expand to
  `_hx_stackframe.lineNumber = n;` (StackContext.h) — executed assignments,
  never registered anywhere queryable — so the runtime alone cannot verify a
  line and a breakpoint on a non-executable line silently never fires.
- **But we can build the table at COMPILE time** (user-spotted): the HXLINE
  values come from typed-AST positions, and our boot macro runs inside that
  same compilation. A `Context.onGenerate` walk collects executable lines per
  file and bakes a `file -> sorted lines` resource into the binary; the
  server then verifies breakpoints line-level AND snaps a non-executable line
  to the next executable one (the HL adapter's resolveLine behaviour, which
  DAP allows). Strictly better than vshaxe and the raw runtime. M2 fixture
  tests must pin the walk's "line with code" notion against what gencpp
  actually emits.

## M3 — run control (shrinks a lot)

- `stepThread(thread, STEP_INTO/OVER/OUT, count)`, `continueThreads(thread,
  count)`, `breakNow(wait)` — all of run control.
- **`ThreadInfo` is the whole stackTrace request**: number, status, and
  `stack:Array<StackFrame{fileName, lineNumber, className, functionName,
  parameters}>`. No stack walker to write — the HashLink StackWalker
  equivalent is a getter.
- **Stop-reason mapping is direct**: `ThreadInfo.status` distinguishes
  BREAK_IMMEDIATE (pause/step) / BREAKPOINT (+ `breakpoint` number!) /
  UNCAUGHT_EXCEPTION / CRITICAL_ERROR (+ `criticalErrorDescription`). The
  HashLink adapter needed trap-address classification for this; here it's a
  field read.
- Step granularity is per-instrumentation-point on depth+line — the
  "surface stops only on line change" policy remains ours (server-side loop:
  re-step while same file+line).

## M4 — variables (shrinks a lot)

- `StackFrame.parameters` already carries name+value pairs for arguments;
  `getStackVariables(thread, frame, unsafe)` + `getStackVariableValue(...)`
  cover locals ("this" included when present — verify in fixture).
- Values arrive as **live `Dynamic`s** — structured expansion is ordinary
  reflection (`Type.typeof`, `Reflect.fields`, `Type.getClass`,
  `Type.getEnumConstructs`), no memory decoding. vshaxe's
  `VariablesPrinter.hx` (MIT) is the reference for display shaping.
- Statics need no debugger API at all: `Type.resolveClass` +
  `Type.getClassFields` + `Reflect.field` — plus `Debugger.getClasses()` to
  enumerate.
- Writes: `setStackVariableValue(thread, frame, name, value:Dynamic, unsafe)`
  — any frame, any constructible value. Returns the value actually set:
  compare to detect silent misses (the vshaxe bug was ignoring this).

## M5 — evaluate

- In-process evaluation is reflection, not memory surgery: identifier →
  `getStackVariableValue`; field access → `Reflect.getProperty` (respects
  getters); calls → `Reflect.callMethod`; construction →
  `Type.createInstance`. The interpreter shell around it is the real work.
- Two reuse candidates for that shell, decision at M5: port vshaxe's
  `eval/Parser+Interp` (MIT, debugger-tailored, proven in this exact context)
  or embed the `hscript` haxelib (mature, but a dependency and broader than
  needed). Our HL `ExprParser` remains a conceptual reference only (no code
  sharing, per standing decision).

## M6 — exceptions (the spike's central question is answered)

- **Every `throw`/`rethrow` in an HXCPP_DEBUGGER build funnels through the
  runtime**: generated code calls `__hxcpp_dbg_checkedThrow`, which runs
  `hx::CanBeCaught(value)` — the runtime walks the enclosing frames'
  DECLARED CATCH TYPES (catchable instrumentation), i.e. real typed
  uncaught-detection, stronger than the HL adapter's "any try counts"
  approximation (`Debugger.cpp:1457-1471`).
- An uncatchable throw becomes `hx::CriticalError` →
  `__hxcpp_dbg_fix_critical_error` → `DoBreak(STOPPED_CRITICAL_ERROR,
  description)` — the thread stops BEFORE unwinding, with the message; our
  handler sees a normal stop event. Uncaught + critical-error breaks are
  therefore nearly free.
- "Break on ALL exceptions" has no runtime hook: checkedThrow only stops for
  uncatchable values. Remaining spike question: whether a caught-throw stop
  can be synthesized without runtime changes (e.g. class-function breakpoints
  on constructors of throwable types — partial at best). Scope typed filters
  onto the uncaught path first.
- `Debug.cpp` also has a settable critical-error handler ("throw from it to
  prevent default action") — potentially the hook for turning fatal errors
  into resumable stops; probe in the spike.

## M7/M8 — no hxcpp findings beyond the above

Fixture builds already proven in the vshaxe module (pinned haxelib, toolchain
probing, path-embedding caveat). `HXCPP_DEBUGGER` + `-debug` are the only
required flags.

## Plan adjustments

- M3/M4 estimates shrink: stack, stop reasons and values are field reads over
  `ThreadInfo`/`Dynamic`, not new machinery.
- M6 spike narrows to two questions: caught-throw synthesis and the critical
  error handler's resume semantics. Typed uncaught filters are in scope.
- New M2 note: breakpoint verification is file-level only (no line tables).
- M5 gains an explicit build-vs-port decision (vshaxe eval vs hscript vs own).
