# intellij-hxcpp-debugger — server internals & gotchas

The in-debuggee DAP debug server (`intellij-hxcpp-debug-server` haxelib). See
`implementation-plan.md` for the milestones and `hxcpp-api-research.md` for what
the runtime provides. This file records the non-obvious behaviours learned
while building it — the things that will bite again.

## 1. The debug-thread registration (breakpoints silently never fire)

`Debugger.setEventNotificationHandler` registers ITS CALLING THREAD as hxcpp's
one debug thread — the single thread excluded from ever breaking. So the
handler MUST be installed on the server thread, never on the main thread; if
main registers it, main becomes the debug thread and no user-code breakpoint
can ever fire.

Startup ordering (`Server.start`/`run`), matching vshaxe's proven sequence:
1. main spawns the server thread and waits (`ready`);
2. the server thread installs the handler (registering itself as the debug
   thread) and `excludeCurrentThread()`, then signals `ready`;
3. only THEN does main `enableCurrentThread()` — hxcpp does not debug a thread
   until it opts in — and park until `configurationDone`.

## 2. Read the stop status ON THE STOPPING THREAD

The stop notification runs on the thread that stopped, while it is genuinely
suspended. Read `getThreadInfo(threadNumber, false)` THERE (in the handler) to
get the status + hit breakpoint number. Reading it later from the server thread
races the thread's state and returns STATUS_RUNNING — a silent misclassified
stop. The full `StopInfo` is captured in the handler and travels with the
event; the server thread only formats and sends it.

## 3. select() for readability, not a read timeout

A blocking socket read that times out surfaces as `haxe.io.Eof` on cpp —
indistinguishable from a real disconnect, which would kill the session loop the
moment configuration finished. Poll with `Socket.select([socket], …, timeout)`
and read only when readable; then an `Eof` from the read genuinely means the
IDE hung up. The loop keeps spinning to drain runtime events while no request
is pending.

## 4. continueThreads takes the STOPPED thread number

`Debugger.continueThreads(specialThreadNumber, continueCount)` continues all
stopped threads; `continueCount` is how many breakpoints the special thread
SKIPS (0 = stop at the next). For a plain resume, pass the stopped thread's
number with count 1. Passing a wildcard/invalid number is wrong.

## 5. A loop-BODY breakpoint does not re-fire each iteration

hxcpp suppresses re-hitting a breakpoint on the SAME source line until
execution leaves that line. A breakpoint on a one-line loop body
(`for (…) total = add(total, i);`) therefore fires ONCE, not once per
iteration — the line never "changes". A breakpoint inside a function CALLED
from the loop fires every call. Tests that need repeated hits use a called
function's body line, never the loop line (see the fixture's `add()`).

This is the runtime's granularity, not something the server can change by
surfacing more stops — it is the flip side of the "step until the line changes"
policy planned for M3.

## 6. Events must not precede the `initialized` event

A debuggee whose main thread already exists fires THREAD_CREATED the instant
its debugging is enabled — before the client has even sent `initialize`. The
Dispatcher buffers all outbound debug events until it has answered initialize
and sent the `initialized` event, then flushes them in arrival order.

## 7. stepThread continues the thread itself; find it by number

`Debugger.stepThread(threadNumber, stepType, 1)` both arms the step AND
continues the stopped thread — do NOT call continueThreads after. It matches
the thread by its debugger NUMBER (getThreadInfos().number), which for the main
thread is 0. STEP_INTO stops at the next line; STEP_OVER/OUT compare stack
depth against the level captured when the thread broke.

## 8. Stepping needs a breakpoint to stay armed (zero-breakpoint limitation)

hxcpp only runs its per-line step/breakpoint check while at least one
breakpoint exists (`gShouldCallHandleBreakpoints`). Consequence: a step issued
when the user has NO breakpoints set never stops — the thread runs to
completion. Stepping is reliable whenever any breakpoint is set (the common
case, and what our tests cover). A sentinel-breakpoint workaround was tried but
did not reliably keep the check armed; robust zero-breakpoint stepping is an
open item (candidate: an execution-trace toggle, if hxcpp exposes one).

## 9. Trim the debugger's own frames from a captured stack

`getThreadInfo(n, false)` is read INSIDE the stop handler (§2), so the captured
stack has this handler's frames on top (getThreadInfo, the notification
closure). Trim everything above the reported stop location — the innermost
frame matching the handler's (file, line, function) — leaving a clean user
stack. Stack order is innermost-LAST; DAP wants newest-first, so it is reversed
when building the stackTrace response.

## 10. Step vs pause: both are BREAK_IMMEDIATE

The runtime reports a step landing and a user pause with the same status
(STOPPED_BREAK_IMMEDIATE). The Dispatcher disambiguates by tracking whether a
step is in flight: a BREAK_IMMEDIATE stop during a step is reason "step" (and
re-steps if the source line has not changed, §5); otherwise it is "pause". A
breakpoint or exception hit mid-step wins over the step.

## 11. Variables are reflection, and writes reach ANY frame

Because the server runs in-process, a local's value is a real Haxe object:
`Values` describes and expands it with `Type.typeof`/`Reflect` (arrays,
objects — data fields only, methods filtered — anon structures, enums), no
memory decoding. This all runs under the interpreter, so it is unit-tested
over plain values.

`setStackVariableValue(thread, frame, name, value)` takes a real frame number,
so the server writes to ANY frame — the fix over vshaxe's top-frame-only,
silently-ignored writes. `VariablesView` owns the DAP variablesReference
registry (a reference names a frame's locals or an expandable value) and
`reset()`s it on every stop, since a reference must never outlive its stop.

Scope for now: one flat "Locals" scope per frame (hxcpp exposes params +
locals + `this` together); setVariable parses bool/int/float/string literals
(constructing objects is out of scope).

## 12. Frame index 0 is the OUTERMOST frame, not the top

hxcpp's stack (in `ThreadInfo.stack` and for `getStackVariables`) is ordered
innermost-LAST, so the frame the debuggee actually stopped in is at
`stack.length - 1`, and index 0 is `__hxcpp_main`. The DAP frame ids we hand
out are these raw hxcpp indices (the trim in gotcha 9 only drops the tail, so
the surviving indices still line up). Anything that evaluates against "the
current frame" — a conditional breakpoint's condition, a frameless
`evaluate` — must therefore default to `stack.length - 1`, NOT 0. Getting this
wrong is silent: `amount` resolves to nothing in `__hxcpp_main`, hscript reads
it as `null`, `null == 2` is `false`, and the conditional breakpoint suppresses
EVERY hit instead of erroring. The DAP `evaluate` request looked fine only
because clients pass an explicit `frameId` from `stackTrace`.

## 13. evaluate is LIVE — hscript is reflection, not a sandbox

hscript does not interpret a copy of the program: every operation bottoms out
in `Reflect` on the REAL values bridged from the frame (`Interp.call` is
literally `Reflect.callMethod(o, f, args)`). So `box.addTo(7)` in a watch runs
the compiled method and mutates the real object, and the change persists after
resume — exactly like evaluate in the Java debugger. Our `ResolvingInterp`
additionally resolves type names, so static calls and `new` work too: bare
identifiers (`Counter.bump(5)`, `Std.int(x)`) resolve at execution time, and
dotted package paths (`my.pack.Target.fn(x)`) — which hscript parses as field
access on the free identifier `my` — are pre-bound by scanning the parsed AST
and materializing each resolvable dotted prefix as nested anonymous objects.
Binding only paths that actually resolve keeps unknown-identifier errors (and
the conditional-breakpoint fail-safe) intact. Verified live: evaluated
`Counter.bump()` and `fix.PackCounter.bump()` calls accumulated static state
across separate evaluate requests against the native fixture.

One boundary to remember: reassigning a frame LOCAL only persists through the
explicit `name = expr` write-back path (hscript's own scope is scratch);
object-field and static mutations need no help. Side effect of liveness: a
careless watch expression can change program behavior; that is inherent to
in-process evaluation.

## 14. hxcpp catches by enum TYPE loosely — hscript errors became null

On hxcpp, a `catch` clause typed to one enum catches ANY thrown enum. hscript's
`Interp.exprReturn` wraps evaluation in `catch(e:Stop)` (its internal
control-flow enum for return/break/continue) — on cpp that also caught
hscript's `Error` enum, matched no `Stop` case, and fell through to `return
null`. Net effect: every runtime evaluate error (unknown identifier, null
access) silently produced `null` on the native target while throwing correctly
under the interpreter — an interpreter-green/native-broken class of bug our
unit tests cannot catch, which is exactly why every milestone is also verified
live. `ResolvingInterp.execute` bypasses `exprReturn` (calls `expr` directly)
and re-handles the `Stop` cases by name, since `Stop` is module-private.
Residual: `exprReturn` is also used inside hscript-defined function bodies, so
an error inside a function DEFINED IN THE WATCH EXPRESSION still nulls on cpp;
not worth reimplementing `EFunction` over.

## 15. Exceptions: one runtime channel, string descriptions, resume asymmetry

The runtime reports EVERY exception-ish stop as `STATUS_STOPPED_CRITICAL_ERROR`
with a description string — `STATUS_STOPPED_UNCAUGHT_EXCEPTION` exists in the
std API but is never emitted by hxcpp 4.3.2 (source-grepped). Two kinds share
the channel, told apart by description:

- **Uncatchable throw** (`"Uncatchable Throw: <value.toString()>"`): every
  `throw` in an HXCPP_DEBUGGER build runs `__hxcpp_dbg_checkedThrow`, which
  walks the enclosing frames' DECLARED catch types (`hx::CanBeCaught`) — real
  typed uncaught-detection. The thread stops AT the throw site BEFORE
  unwinding, so the full stack and locals are inspectable (verified live).
  Continue unwinds and terminates normally (`Error : <value>`).
- **Critical error** (`"Null Object Reference"`, GC errors, ...): with a
  debugger attached `hx::NullReference` calls `__hxcpp_dbg_fix_critical_error`
  UNCONDITIONALLY — a null access stops even inside a try/catch that would
  have caught it (verified live), a deliberate behavior difference from an
  undebugged run. Resume is NOT clean: the "fixup" path re-executes the
  faulting access, so continue re-stops or hard-crashes (0xC0000005 observed).
  With the "critical" filter off, the Dispatcher caps consecutive silent
  resumes (MAX_SILENT_CRITICAL_RESUMES) so a resumable fault loop cannot
  livelock the session.

NOT SUPPORTABLE (and why):

- **Break on caught/all exceptions** — no runtime hook: `checkedThrow` only
  calls out when the value is uncatchable; a catchable throw is a plain
  `hx::Throw` with no debugger involvement.
- **Typed exception filters** — the thrown VALUE is never surfaced (it lives
  as a C++ local in `checkedThrow`); only its `toString()` embedded in the
  description survives, which is not reliably a class name.

## Diagnostics

Set the `HXCPP_DEBUG_LOG` env var to a file path to get a low-tech append log
of the server's lifecycle (`Server.log`) — the practical way to trace an opaque
multi-threaded live session. Off (no env var) by default.
