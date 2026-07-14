# HXCPP debugger internals & gotchas

Non-obvious problems hit while building the HXCPP debugger and the reasoning
behind the fixes, in the spirit of the HashLink module's gotchas doc. Read
before touching the launch/port/lifecycle code.

The module is named `vshaxe-hxcpp-debugger-adapter` because it specifically
targets the VSHAXE hxcpp debug server (vshaxe/hxcpp-debugger's
hxcpp-debug-server haxelib) — see docs/future-native-dap-server.md for the
possible second, home-grown server this naming keeps unambiguous.

---

## 1. A leftover debuggee instance poisons the debug port (the first IDE test failure)

### Symptom
Every debug session fails: the spawned program prints
`Failed to connect to vsc debugger server at 127.0.0.1:<port>`, runs to
completion, then dies with `Critical Error: Uncatchable Throw: Bind failed`
(exit 0xC0000005). The IDE side times out waiting for the `launch` response.

### Cause
The debug server compiled into the program does two things at startup
(Server.hx): try to CONNECT OUT to the debugger; if that fails, BIND the port
itself and wait for a debugger to attach (`waitForAttach`), on a thread that
keeps the process alive forever — so a plain Run of a debug build "never
exits" after main() completes.

That leftover instance holds the port in a state where later instances can
neither connect to it nor bind it themselves: each newly spawned debuggee
crashes on startup with `Bind failed`. Reproduced exactly: start the fixture
once with no debugger listening (the "orphan"), start it again → the second
instance prints precisely the failure sequence above.

### Fixes
- `HxcppDebugAdapter` binds with `SO_REUSEADDR` off, so an occupied port
  fails the session upfront with the port-busy message instead of silently
  double-binding (Windows would otherwise sometimes allow it).
- `HxcppDebugProcess` watches the debuggee's process handler: death before
  the `launch` handshake completes fails the session IMMEDIATELY with the
  exit code and the leftover-instance hint, instead of a 30 s timeout.
- The run-configuration hint warns that debug builds wait for a debugger
  under plain Run and may never exit.

### Where this bites again
Anything that leaves debuggee processes behind (crashed sessions, killed
IDE) recreates the poisoned port. If sessions start failing mysteriously,
`Get-Process` for the program name first.

---

## 2. The wire protocol echoes requests as responses

`Server.hx` answers by sending the REQUEST OBJECT back with `result`/`error`
filled in, so responses carry the request's `method` and `params` too —
message classification must key on the presence of `id` alone
(JsonRpcJson). Every request gets a response, including Void-result methods
like `pause`/`continue`.

## 3. Native frames report "?" as their source path

Stack frames without Haxe source (native/system frames) carry `"?"` — in
path-mangled forms that make `Path.of` throw. Treat any unparseable source
as "no source" (frame without navigation), never as an error that fails the
whole stackTrace request.

## 4. Breakpoint file matching is EXACT-string (the silent no-stop bug)

### Symptom
Breakpoints show as set in the IDE but the program never stops on them.
Everything else (launch, console, termination) works.

### Cause
Server.hx resolves a breakpoint's file by exact string lookup against the
compiler-recorded full paths (`path2file[path2Key(params.file)]`, where
path2Key only UPPERCASES on Windows — no separator normalization, no suffix
matching). IntelliJ's VirtualFile paths use FORWARD slashes on Windows
(`C:/Users/...`); the compiler records backslashes. The lookup misses, the
breakpoint is registered against a null file, and there is no error — the
server happily returns an id.

### Fix
The adapter converts client paths to native separators before every
setBreakpoints (`toDebuggerPath`). The launch integration test deliberately
sends IDE-shaped forward-slash paths so a real stop pins the conversion.

### Where this bites again
Any new request that carries a file path to the server needs the same
conversion. And the match is still EXACT full-path: an executable compiled
from sources at a different location than the project opened in the IDE
(moved project, CI build) will not match — suffix matching would need
server-side support (Debugger.getFilesFullPath is not exposed over the
protocol).

## 5. evaluate is read-only, setVariable writes ONLY the top frame

### Symptom
`n = 100` in the IDE's evaluate box shows "n = 100" as if it worked, but the
program's behaviour and the Variables view are unchanged.

### Cause (two independent server facts)
- The server's `evaluate` never writes: its interpreter computes the
  expression's value (assignment included) without touching the debuggee.
  Writes must go through the `setVariable` method, whose value parameter is
  a LITERAL (quotes stripped; not evaluated).
- `setVariable` HARDCODES the top stack frame of the stopped thread
  (`currentThreadInfo.stack.length - 3` in Server.hx). `switchFrame` does
  not change that, and a variable that does not exist in the top frame is
  silently ignored — the server still reports success.

### Fix
The adapter recognises top-level assignments in evaluate (`topLevelAssignment`
— outside quotes/brackets, not a comparison), evaluates a non-literal right
side first, routes the write through `setVariable`, and VERIFIES every write
(evaluate + F2 setValue) by re-reading the target: an unchanged value becomes
an error naming the top-frame-only limitation instead of a silent lie. The
IDE-side evaluator refreshes the variable views after a successful assignment.

### Where this bites again
Only variables of the STOPPED function can be modified. Anything deeper
(caller locals, and possibly object fields through paths the server's
setStackVariableValue does not resolve) is refused with the explanatory
error. Lifting this needs an upstream server change.

## 6. Multi-expression lines hit their breakpoint once per expression

### Symptom
A breakpoint on a line containing embedded iteration — e.g. an array
comprehension `var items = [for (i in 0...n) i * 10];` — stops once per
iteration, not once per line. Stepping over such a line re-lands on it
repeatedly, and plain run-to-cursor keeps getting intercepted by it
(breakpoints win over the run-to target by design, same as IntelliJ's Java
debugger).

### Cause
hxcpp traps at EXPRESSION granularity: every executed sub-expression of the
line re-enters the breakpoint. This is server/runtime behaviour (the VSCode
debugger has it too), not something the adapter can reliably filter — a
loop-body line legitimately re-hits every iteration, and there is no way to
tell "same statement, next comprehension iteration" from "next loop pass"
at the protocol level.

### Workarounds / status
Force Run to Cursor works: the platform temporarily unregisters breakpoints
through our handler, so the server has none armed. A possible future
improvement is a "step until the line changes" loop in the debug process
(auto-repeat step while file:line is unchanged) — deliberate, opt-in,
because it would hide intermediate state like the comprehension's `i`.

## 7. Unknown jsonrpc methods return a null-result SUCCESS

Server.hx's dispatch handles only a subset of Protocol.hx: `switchFrame`,
`setExceptionOptions`, `setBreakpoint` and `removeBreakpoint` have NO
handler, and an unhandled method falls through to a response with a null
result and no error. A call to them "succeeds" while doing nothing — this
masked our misuse of switchFrame for a while. Never rely on a
success response as proof a method exists; check the dispatch first.
Consequence: `setExceptionBreakpoints` is an honest adapter-side no-op.

## 8. Uncaught exceptions: stop first (as "pause"), classify later

With the debugger attached, an uncaught throw STOPS the debuggee at the
throw line — but the server reports that first stop as `pauseStop`, not
`exceptionStop` (the critical-error classification happens later in the
unwind). Continuing then yields an exception-reason stop carrying the
thrown text and/or the process dying with its Critical Error output; the
final continue races the process's death (an failed continue there is
expected). Caught exceptions never stop, and there is nothing to configure.
Pinned by HxcppUncaughtExceptionIntegrationTest.

## 9. Fixture builds and the compile-time port

HXCPP_DEBUG_HOST/HXCPP_DEBUG_PORT are compile-time defines
(`Context.definedValue`), not runtime configuration. The test fixture pins
port 6973 (non-default) so tests never collide with a real session on 6972;
integration tests serialize on that port.

## 10. Moving/renaming this module breaks fixture builds two ways

- hxcpp object files embed absolute paths: after any directory change the
  incremental link fails with `LNK2011: precompiled object not linked in` —
  delete `build/hxcpp` once and rebuild.
- Windows MAX_PATH (260): haxe writes generated files with paths built from
  its cwd WITHOUT normalizing, so a `test-fixtures/../` segment counts
  toward the limit. The fixture tasks therefore run haxe from the MODULE
  ROOT with `test-fixtures/`-relative hxml paths; the longest generated
  name (GenericStackIterator_hxcpp_debug_jsonrpc_eval_Token.cpp) sits close
  enough to the limit that a deeper module path plus the unnormalized
  segment failed with a misleading `Sys_error(... No such file or
  directory)` while the file plainly existed.
