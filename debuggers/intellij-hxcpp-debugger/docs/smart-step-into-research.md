# Smart step into on hxcpp — research (2026-07-15)

Question: can our in-debuggee server support "choose which call on this line to
step into" (the HashLink smart-step feature)? Answer: **yes, with a different
division of labour** — the runtime primitives compose into exactly the
HashLink semantics, but target discovery must move to the IDE.

## What the runtime gives us (verified in hxcpp 4.3.2 `src/hx/Debugger.cpp`)

1. **Steps and breakpoints are evaluated together.** Every instrumentation
   point runs `HandleBreakpoints`: the step check runs first
   (`STEP_INTO`/`STEP_OVER` by stack depth), and only if the step does not
   claim the stop are the installed breakpoints checked (lines 572–654). So a
   temporary breakpoint is live DURING a step — "whichever lands first wins"
   needs no runtime changes.
2. **Class/function breakpoints fire at function entry.** They match
   `hash(className + "." + functionName)` against every frame's precomputed
   `classFuncHash`, and only when `frame->lineNumber == firstLineNumber`
   (lines 639–644). That is precisely a "callee entry" landing — the same
   thing the HashLink adapter plants at opcode 0 of the callee.
3. **The names are knowable.** Generated code instruments every function with
   `HX_LOCAL_STACK_FRAME(..., "Main", "accumulate", ..., "Main.accumulate",
   "Main.hx", 37, ...)` — dotted class name + bare function name (verified in
   our generated fixture C++, including std classes like `Sys.sleep`, which
   means stepping into instrumented std code also works).

## The mechanism

The recipe, mirroring HashLink's targeted step:

- **IDE side computes the targets** (the server cannot: it has no line→calls
  knowledge, unlike the HL adapter which read bytecode). The
  `XSmartStepIntoHandler` walks the Haxe PSI for call expressions on the
  stopped line — we already do this matching for the HL highlight ranges —
  and RESOLVES each callee to its declaring class, yielding
  `(className, functionName)` variants with editor highlight ranges. PSI
  resolution is the IDE's home turf.
- **On selection**, send the server a custom request (e.g.
  `intellij/stepIntoFunction` with `{threadId, className, functionName}`).
- **Server**: install a temporary `addClassFunctionBreakpoint(className,
  functionName)`, issue `stepThread(STEP_OVER)`, and on the next stop delete
  the temp and report a plain step stop — whether the temp fired (entered the
  chosen callee, possibly running through earlier calls on the line) or the
  step-over landed first (the chosen call never executed: short-circuit,
  conditional). The fallback-degrades-to-step-over behaviour is identical to
  the HashLink implementation and safe by construction. The temp must be
  excluded from user-breakpoint bookkeeping.

## Cases and mitigations

| Case | Status |
|---|---|
| Static and instance methods | works — names verified in generated code |
| Recursion / re-entry | works — entry matching fires on the new frame |
| Virtual dispatch (override called via base type) | bp on the base class name misses the override's frame — fan the temp out to all known overrides (PSI subclass search); an unfired extra temp costs nothing |
| `inline` functions | no runtime function exists — the IDE filters these variants out (PSI knows the modifier) |
| Property accessors | resolve to the real `get_x`/`set_x` names |
| Externs / non-instrumented natives | no frame instrumentation, the temp can never fire — variant lands as step-over (safe); ideally filtered IDE-side |
| Closures / local functions | naming convention unverified (fixture had none) — **needs an empirical check with a richer fixture during the milestone spike**; worst case these variants are filtered out |

## What to build when the milestone comes

1. Server: temp class-function breakpoint bookkeeping + the custom request
   (small — the pieces exist by M3).
2. IDE: extend the smart-step handler pattern from
   `HashLinkSmartStepIntoHandler` — same UI, but variants computed from PSI
   resolve instead of a `stepInTargets` round-trip, sending
   `(className, functionName)` instead of an opcode id.
3. Fixture with closures, overrides, inline and accessor calls to pin the
   naming table above.

Conclusion: feasible with confidence; schedule after M3 (run control) and the
M7 IDE wiring, since it needs both.
