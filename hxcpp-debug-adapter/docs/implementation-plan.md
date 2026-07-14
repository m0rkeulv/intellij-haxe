# HXCPP debugger — implementation plan

Status: DRAFT — awaiting sign-off before any implementation.

This plan is based on research of the vshaxe debugger (github.com/vshaxe/hxcpp-debugger,
the `hxcpp.debug.jsonrpc` server library) and of our own HashLink debugger
(`hashlink-debug-adapter` + `com.intellij.plugins.haxe.hashlink`).

---

## 1. Verified facts the design rests on

### The vshaxe design
- The debugged application is compiled with `-lib hxcpp-debug-server` and `-debug`
  (cpp target). The lib's `extraParams.hxml` runs `Macro.injectServer()`, which
  compiles in `hxcpp.debug.jsonrpc.Server` and defines `HXCPP_DEBUGGER`.
- At app startup the Server runs on a dedicated debugger thread. It first tries to
  **connect out** to the debugger at `127.0.0.1:6972`; if that fails it can fall back
  to **listening** for an attach. Host/port are **compile-time defines**
  (`HXCPP_DEBUG_HOST` / `HXCPP_DEBUG_PORT`), not runtime configuration.
- Launch flow in the VSCode variant: the adapter listens on 6972, spawns the exe,
  the app's Server connects back, `Debugger.breakNow(true)` holds the app stopped,
  the adapter applies queued `setBreakpoints`, then sends `continue` — so
  breakpoints are always in place before user code runs.
- The Server does the heavy lifting **inside the debuggee** via hxcpp's built-in
  `cpp.vm.Debugger` API (breakpoints, stepping, stack, variables, conditional
  breakpoints with its own expression interpreter). This is the crucial difference
  from HashLink, where our adapter had to implement the low-level debugger itself
  (INT3 patching, memory layout decoding...). **Our HXCPP layer is a protocol
  translator, not a debugger** — an order of magnitude less machinery.

### The wire protocol (`hxcpp.debug.jsonrpc`)
- Framing: 4-byte little-endian length prefix + UTF-8 JSON body.
- Requests `{id, method, params}` → responses `{id, result}` or `{id, error:{code,message}}`.
  Notifications from the server have no id.
- Methods: `pause`, `continue{threadId}`, `stepIn`, `next`, `stepOut`,
  `stackTrace{threadId}`, `setBreakpoints`, `setBreakpoint`, `removeBreakpoint`,
  `switchFrame{id}`, `getScopes{frameId}`, `getVariables{variablesReference,?start,?count}`,
  `setVariable{expr,value}`, `threads`, `evaluate{expr,frameId}`, `completions`,
  `setExceptionOptions`.
- Notifications: `breakpointStop{threadId}`, `exceptionStop{text}`,
  `pauseStop{threadId}`, `threadStart{threadId}`, `ThreadExit{threadId}`
  (capitalisation is the protocol's, not a typo).

### Known impedance mismatches with DAP (the translation layer's real work)
| DAP | jsonrpc protocol | Translation |
|---|---|---|
| `next/stepIn/stepOut(threadId)` | no threadId; stepping acts on the server's current thread | track/assert the stopped thread; steps only valid while stopped |
| `setVariable(variablesReference, name, value)` | `setVariable(expr, value)` — expression path | reconstruct an expression path from the reference tree (the vshaxe Adapter.hx does the same) |
| `stopped(exception)` carries threadId | `exceptionStop{text}` has no threadId | report the stopped thread (vshaxe reports 0) |
| `variablesReference` lifetime per stop | server clears its references on each stop | mirror DAP rules; never reuse references across stops |
| adapter chooses port per session | port is a **compile-time define** in the debuggee | see "port strategy" below |

## 2. Architecture and module layout

Mirrors the vshaxe design (proven: DAP front, jsonrpc back), but the adapter is
**in-process Java** — no Node.js, no external process, no stdio plumbing.

```
+----------------------------- IntelliJ plugin (src/main) ------------------------------+
| HxcppRunConfiguration / HxcppDebugRunner                                              |
| HxcppDebugProcess (XDebugProcess)  — DAP client, mirrors HashLinkDebugProcess         |
+------------------------------------|---------------------------------------------------+
                                     | DAP  (in-process, via dap-protocol module)
+------------------------------------v---------------------------------------------------+
| :hxcpp-debug-adapter   HxcppDebugAdapter — implements the DAP server surface           |
|                        translator: DAP request <-> jsonrpc call, event <-> notification|
|                        jsonrpc client: framing + messages (own testable package)       |
+------------------------------------|---------------------------------------------------+
                                     | custom JSON protocol over TCP (length-prefixed)
+------------------------------------v---------------------------------------------------+
| debuggee exe, compiled with -lib hxcpp-debug-server -debug                             |
| hxcpp.debug.jsonrpc.Server on its debugger thread, driving cpp.vm.Debugger            |
+----------------------------------------------------------------------------------------+
```

Gradle modules after this work:

| Module | Content |
|---|---|
| `:dap-protocol` (new) | generic DAP Java code moved out of `:hashlink-debug-adapter`: `DapClient`, `DapConnection`, `DapJson`, protocol POJOs. No IntelliJ dependencies beyond what it has today. |
| `:hxcpp-debug-adapter` (new) | `jsonrpc` package (framing, messages, client — zero IDE deps) + `adapter` package (the DAP↔jsonrpc translator). Depends on `:dap-protocol`. |
| `:hashlink-debug-adapter` | unchanged except imports now point at `:dap-protocol`. Haxe adapter untouched. |
| `:hxcpp-debugger-protocol-legacy` (renamed) | the old generated legacy protocol, wired exactly as before. Untouched otherwise. |
| plugin (`src/main`) | new `com.intellij.plugins.haxe.hxcpp` package: run config, runner, `HxcppDebugProcess` + breakpoint/stack/value classes mirroring the `hashlink` package. **No code shared with hashlink or legacy debugger code (per project rules).** |

The DAP boundary between plugin and adapter: `HxcppDebugProcess` uses the same
`DapClient` the HashLink side uses, connected to the in-process adapter through an
in-memory `DapConnection` (loopback pipe). Everything below the DAP line is
IDE-free and unit-testable; everything above it is DAP-only and never sees jsonrpc.

## 3. Session flow (launch)

1. Run config resolves the compiled exe (project output; validation that it was
   built with `-debug` + the lib happens at runtime — a connect timeout produces a
   clear error message).
2. `HxcppDebugRunner` starts the adapter component; the adapter opens a TCP
   **listener** (see port strategy), then the runner spawns the exe via the IDE's
   ProcessHandler (console/stdin/exit code flow through normal run machinery —
   same decision as HashLink attach mode; also avoids any SW_HIDE-class surprises).
3. The debuggee's Server connects back; the app is held stopped by its initial
   `breakNow`.
4. `HxcppDebugProcess` runs DAP `initialize`/`setBreakpoints`/`configurationDone`;
   the adapter translates to jsonrpc `setBreakpoints` and finally `continue`.
5. Events flow: jsonrpc notifications → DAP events → XDebugSession positions.
6. Stop: DAP `disconnect` → adapter closes the socket, runner kills the process if
   still alive.

**Port strategy (decided):** host and port are **run-configuration fields,
prefilled with the protocol defaults** `127.0.0.1` / `6972`, so the default flow
needs no configuration at all. They are compile-time defines in the debuggee
(verified: `Macro.getDefinedValue` = `Context.definedValue`, a macro), so when the
run configuration triggers the build it appends `-D HXCPP_DEBUG_HOST=<host>
-D HXCPP_DEBUG_PORT=<port>` whenever they differ from the defaults — one place to
configure, build and debugger always agree. An exe built outside the IDE with a
custom port must be compiled with the matching defines (documented in the run
config UI help). Test fixtures compile once with a fixed non-default port and the
integration tests serialize on it.

## 4. Testing strategy

Follows the HashLink module's proven pattern (graceful skip when toolchain missing,
fixture paths via system properties).

- **Unit (pure Java, always run):** jsonrpc framing encode/decode; translator tests
  driving `HxcppDebugAdapter` with DAP requests against a scripted fake jsonrpc
  server (in-memory socket) — the mismatch table above gets a test per row.
- **Integration (need haxe + hxcpp + C++ toolchain):** fixture Haxe programs
  compiled to windows exes with `-lib hxcpp-debug-server -debug
  -D HXCPP_DEBUG_PORT=<fixed test port>`; tests drive the full DAP surface against
  the real Server: launch-hold-continue, breakpoint hit/condition, step in/over/out,
  threads, stack, scopes/variables, evaluate, setVariable, disconnect.
  hxcpp compilation is slow (minutes, not HL's seconds) — keep the fixture count
  low and reuse one exe across many tests.
- **Gradle:** `:hxcpp-debug-adapter` gets fixture-build tasks modeled on
  `hashlink-debug-adapter/build.gradle.kts` (`onlyIf { haxeAvailable }`,
  `inputs`/`outputs`, warn-and-skip).

**User-provided environment (needed before integration tests can run):** Haxe +
haxelib on PATH, `hxcpp` and `hxcpp-debug-server` haxelibs installed, and a C++
toolchain hxcpp can use (Visual Studio Build Tools on Windows). I'll ask when we
reach M3.

## 5. Milestones

Each milestone compiles and its tests pass before moving on.

- **M0 — module surgery.** Rename `:hxcpp-debugger-protocol` →
  `:hxcpp-debugger-protocol-legacy`; create `:dap-protocol` and move the generic DAP
  Java code into it; `:hashlink-debug-adapter` depends on it. Gate: full build green,
  HashLink debugger unaffected (its integration tests still pass on this machine).
- **M1 — jsonrpc client.** Framing, message model, request/response correlation,
  notification dispatch. Gate: unit tests incl. torn-frame/partial-read cases.
- **M2 — the adapter.** `HxcppDebugAdapter` translating the full DAP surface used by
  the IDE (initialize→disconnect). Gate: translator unit tests against the fake
  server; behaviour cross-checked against vshaxe `Adapter.hx`.
- **M3 — first real contact.** Fixture exe + launch-breakpoint-continue-exit
  integration test against the real Server. **Needs the user's toolchain setup.**
- **M4 — plugin integration.** Run configuration, runner, `HxcppDebugProcess` and
  XDebugger classes; manual IDE verification by the user. Gate: breakpoint →
  stack → variables → step → resume → stop works in a sandbox IDE.
- **M5 — full surface.** Conditional breakpoints, evaluate (watches), setVariable,
  threads UI, exception stops (`setExceptionOptions`), completions if the IDE
  surface wants it. Integration tests per feature.
- **M6 — polish.** Error paths (exe without debug server, port busy, app crash),
  docs (`docs/README.md` gotchas file like HashLink's), CHANGELOG, and the
  "legacy" labelling of the old HXCPP debugger's user-visible text (without
  touching its Flash/Flex debugging text or behaviour — see §6.3).

Deferred by design: extracting the common IDE-bridge layer from the HashLink and
HXCPP debug processes — only after both are stable (rule of two).

## 6. Decisions (signed off)

1. **Port/host:** configurable per run configuration with the defaults prefilled
   (`127.0.0.1:6972`); the run config injects the matching `-D` defines into builds
   it triggers. See "Port strategy" in §3.
2. **Attach mode:** deferred — launch-only in v1.
3. **Legacy debugger: stays wired in.** The old debug configuration also carries
   **Flash/Flex debugging, which must not break**, and existing users of the old
   HXCPP debugger need a transition period. We only update user-visible text so the
   old HXCPP path is clearly labelled "legacy" — carefully, so no Flash/Flex-facing
   text or behaviour changes (M6).
4. **hxcpp-debug-server:** pin the latest haxelib release (what the average user
   gets from `haxelib install`); bump deliberately.
