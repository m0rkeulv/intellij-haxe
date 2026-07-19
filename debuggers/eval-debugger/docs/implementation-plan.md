# Eval (interpreter/macro) debugger — research & implementation plan

Debug support for Haxe's **eval** target: `--interp` scripts and **macros**.
Prior art: [vshaxe/eval-debugger](https://github.com/vshaxe/eval-debugger)
(VS Code's DAP adapter for the same backend). The debug server itself is
built into the Haxe compiler (`src/macro/eval/evalDebugSocket.ml`), enabled
by a define — no library, no runtime, nothing to inject into user code.

## Protocol research (verified 2026-07-18)

Everything below was confirmed **empirically against haxe 4.3.7** with a
socket probe (scratch `eval-probe/`), cross-checked against
`evalDebugSocket.ml` + `src/core/socket.ml` (compiler side) and
eval-debugger's `Protocol.hx`/`Connection.hx` (client side).

### Launch & connection
- We open a TCP **listener**, then launch
  `haxe <build args> -D eval-debugger=127.0.0.1:<port> --interp`.
- The eval VM **connects out to us** before executing `main()`, then **waits**
  — user code does not start until the debugger sends `continue`. (This gives
  stop-on-entry semantics for free; no attach race.)
- Works identically for macro debugging: any compilation whose macros should
  be debugged gets the define; breakpoints in macro code hit during typing.

### Wire format — ASYMMETRIC framing (the one trap)
- **Client → VM (requests)**: `[2-byte LE length][UTF-8 JSON]`
  (`Socket.read_string` reads a ui16 prefix).
- **VM → client (responses/events)**: `[4-byte LE length][UTF-8 JSON]`
  (`send_string` writes an i32 prefix).
- Payloads are strict **JSON-RPC 2.0**: requests
  `{"jsonrpc":"2.0","id":N,"method":"...","params":{...}}`; responses carry
  `result` or `error{code,message}`; **events** are id-less notifications.
- Verified round trip: `getThreads` → `[{"id":0,"name":"Thread 0"}]`.
- Max request size 64KB (ui16) — fine for our request shapes.

### Method set (wire names, from Protocol.hx)
Run control: `pause`, `continue`, `stepIn`, `next`, `stepOut` (all
`{?threadId}`). Introspection: `getThreads`, `stackTrace`, `getScopes`
`{frameId}`, `getVariables`, `setVariable`, `evaluate`, `getCompletion`.
Breakpoints: `setBreakpoints` (per file), `setFunctionBreakpoints`,
`setBreakpoint`/`removeBreakpoint`, `setExceptionOptions` (list of filters).
Events: `breakpointStop{threadId}`, `exceptionStop{threadId,text}`,
`threadEvent{threadId,reason}`.

### Data shapes
- `StackFrameInfo {id, name, source:Null<String>, line, column, endLine,
  endColumn, artificial:Bool}` — positions are 1-based with real column info
  (richer than our other targets).
- `ScopeInfo {id, name, ?pos{...}}`; `VarInfo {id, name, type, value,
  numChildren, ?generated, ?line, ?column}` — children are fetched by id,
  pagination-free.

## Architecture decision

**Follow the vshaxe-hxcpp-debugger-adapter pattern**: a Java, in-plugin
translator that presents **DAP** to our existing IDE debug stack and speaks
the eval JSON-RPC protocol to the VM. Rationale:

- Our IDE side (XDebugger integration, breakpoints UI, variables view,
  evaluator, toString toggle) is already DAP-driven and battle-tested across
  three backends; translating eval→DAP reuses all of it.
- `debuggers/vshaxe-hxcpp-debugger-adapter` already contains 90% of the
  plumbing for the SAME protocol family: `JsonRpcClient/Connection/...`
  (its 4-byte LE framing matches the VM→client direction; the 2-byte request
  direction is a small addition), and `HxcppProtocol`'s
  `ScopeInfo/StackFrameInfo/VarInfo` types are near-identical to eval's.
- No new runtime dependency (vs. running vshaxe's Node-based adapter), no
  compiled artifact to ship (vs. a Haxe-built adapter like the HL one) —
  the debug server ships inside every haxe >= 4.0.

Module layout: new `debuggers/eval-debugger` gradle module (this doc lives
there), Java only, depending on `dap-protocol`; jsonrpc classes either
extracted to a shared location or the eval variants kept module-local
(decide at M1 when the reuse surface is concrete).

## Milestones

- **M0 (done)**: protocol probe — framing, connect direction, wait-on-start,
  live `getThreads` round trip.
- **M1 — protocol layer**: framing codec (asymmetric), JSON-RPC client with
  id correlation + event routing, typed method wrappers, unit tests over
  in-memory streams; one live smoke test (launch `haxe --interp`, getThreads,
  continue, clean exit).
- **M2 — DAP adapter core**: launch/initialize/configurationDone lifecycle,
  run control (continue/step/pause), `breakpointStop`/`exceptionStop` →
  DAP stopped events, stackTrace/scopes/variables translation, output
  forwarding (haxe stdout/stderr → DAP output events), clean terminate.
- **M3 — IDE wiring**: run configuration (decision below), program runner +
  XDebugProcess reuse, source mapping (eval sources are the PROJECT sources —
  no compiled-position mapping needed, columns available).
- **M4 — evaluate/setVariable/completion**: expression evaluator hookup,
  variables-view edits, completion via `getCompletion` in the evaluate view.
- **M5 — exceptions + polish**: `setExceptionOptions` mapping to our
  exception-breakpoint UI, `artificial` frame rendering, macro-debug entry
  point (debug a build's macros), integration test suite (fixtures are plain
  .hx scripts run under `--interp` — no compilation artifacts, very fast).

## Decisions (signed off 2026-07-18)

1. **Run configuration UX**: a NEW "Haxe interpreter (--interp)" run
   configuration type, mirroring the HashLink config pattern.
2. **Macro debugging**: design for BOTH scripts and macros from the start;
   implement/validate scripts first. VERIFIED (probe): with an init macro
   and `-D eval-debugger`, the VM connects DURING COMPILATION and answers
   requests — macro debugging uses the identical protocol. Caveat agreed
   with the user: if important differences surface (a macro session wraps a
   COMPILATION and ends with it, vs. a script session wrapping a program
   run), we may split them into two run-config flavours/debuggers later —
   keep launch/session code factored so that split stays cheap.
3. **jsonrpc code sharing**: decide at M1 by measuring the actual diff
   (framing asymmetry + strict jsonrpc envelope may make sharing awkward);
   an eval-local implementation is acceptable if the shared surface is thin.

## Version notes

- Eval debugging requires haxe >= 4.0 (define exists since then); our floor
  is 4.1 — no extra constraint. Protocol shape verified on 4.3.7; matrix
  coverage across 4.1–5p1 lands with the M5 test suite (the harness's
  per-version fixture pattern applies directly — fixtures are just .hx).
- No HashLink involvement at all: the eval VM lives inside haxe.exe.
