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

## Diagnostics

Set the `HXCPP_DEBUG_LOG` env var to a file path to get a low-tech append log
of the server's lifecycle (`Server.log`) — the practical way to trace an opaque
multi-threaded live session. Off (no env var) by default.
