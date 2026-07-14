# HXCPP debugger internals & gotchas

Non-obvious problems hit while building the HXCPP debugger and the reasoning
behind the fixes, in the spirit of the HashLink module's gotchas doc. Read
before touching the launch/port/lifecycle code.

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

## 4. Fixture builds and the compile-time port

HXCPP_DEBUG_HOST/HXCPP_DEBUG_PORT are compile-time defines
(`Context.definedValue`), not runtime configuration. The test fixture pins
port 6973 (non-default) so tests never collide with a real session on 6972;
integration tests serialize on that port.
