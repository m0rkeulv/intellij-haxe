# Future direction: our own native-DAP hxcpp debug server

Status: IDEA — not scheduled. The vshaxe variant remains the supported path
(it is the widely adopted hxcpp debugger); this records why and how we might
build our own debug server later, so the reasoning is not lost.

## Why consider it

The vshaxe hxcpp-debugger's limitations that we have personally hit are
almost all in its SERVER CODE (Server.hx), one layer above the hxcpp
runtime — the runtime API is more capable than the server exposes:

- **Writes are top-frame only** — but `cpp.vm.Debugger.setStackVariableValue
  (threadNumber, stackFrameNumber, name, value, unsafe)` takes a frame
  number (verified in haxe std cpp/vm/Debugger.hx). The server hardcodes the
  top frame and silently ignores misses (gotcha #5 in README.md).
- **Host/port are compile-time defines** — a server could read
  `Sys.getEnv("HXCPP_DEBUG_PORT")` in its static init (fallback to the
  define, then the default) with zero impact on user code. The IDE runner
  sets env vars on the spawned process, enabling an EPHEMERAL PORT PER
  SESSION — eliminating the whole port-collision/poisoning bug class
  (gotcha #1).
- **Breakpoint files are matched by exact full path** (gotcha #4) — a server
  could suffix-match against Debugger.getFilesFullPath, fixing moved
  projects and CI-built executables.
- **Exception breakpoints are stubbed**, responses echo the request object,
  failed writes report success, evaluate cannot assign.
- **Expression-granularity stops** (gotcha #6): the server chooses which
  runtime stop events to surface — "suppress same-line re-hits" and
  "step until the line changes" are simple server-side policies.

## Key insight: the server runs INSIDE the debuggee

Unlike the HashLink adapter (an external process poking raw memory), an
hxcpp debug server is Haxe code compiled into the program. Creating values
is ordinary Haxe: a new String is heap-allocated and GC-rooted by
construction; `setStackVariableValue` takes `value:Dynamic`, so real objects
can be constructed with `new` and handed over. There is no frame-resizing
concern — hxcpp's debug instrumentation registers POINTERS to locals
(HX_STACK_VAR), so writing a string local assigns a heap handle. Numbers,
bools, strings, whole objects: all writable, any frame.

## Speak DAP natively

The new server would speak DAP over the socket (Content-Length framing,
JSON bodies) instead of the custom jsonrpc protocol:

- Our IDE side is ALREADY a pure DAP client; `HxcppDebugProcess` would
  connect its DapClient directly to the debuggee — the in-process adapter
  is simply bypassed. Everything built in M0–M4 survives.
- variablesReference bookkeeping moves into the server where it belongs.
- The two protocols are auto-detectable from the first bytes on the wire:
  DAP starts with an ASCII `Content-Length:` header, the vshaxe protocol
  with a 4-byte little-endian length prefix.

## Build it as a FORK, not from scratch

Fork vshaxe's hxcpp-debug-server: its cpp.vm.Debugger plumbing, threading
model (debugger thread + stateMutex/socketMutex) and expression interpreter
are proven; replace the protocol layer, fix the write paths, add the env-var
config. ~10 files. Publish as our own haxelib (users swap
`-lib hxcpp-debug-server` for ours); keep full vshaxe-protocol support in
the IDE regardless.

## What a new server does NOT fix

Compile-time `-debug` + instrumentation overhead, and needing the lib in the
build, live in hxcpp itself. The trap granularity is also hxcpp's — the
server can only choose which stops to SURFACE, not avoid the underlying
per-expression checks.

## Cheap upstream wins worth PRing regardless

- env-var override for HXCPP_DEBUG_HOST/PORT (few lines, Server.hx)
- pass the real frame number to setStackVariableValue
- suffix-based breakpoint file matching

These help us even if the fork never happens; vshaxe review latency makes
them a bonus, not a dependency.

## Naming

DONE: the module is `vshaxe-hxcpp-debugger-adapter` and its user-facing
descriptions say it targets the VSHAXE debug server, so a future home-grown
server can take an unambiguous name of its own.
