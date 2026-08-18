# Chunks 5-8 — debugger modules (523 files)

Calibration: dap-protocol, hashlink-debug-adapter, intellij-hxcpp-debugger,
vshaxe adapter, eval-debugger, browser-debugger and compat-matrix are all
rules-era modules (developed under CLAUDE.md with module READMEs, live
suites and the compat matrix). Pattern-swept rather than re-read; clean
except the items below. hxcpp-debugger-protocol-legacy is VENDORED
generated code — [vendored], no findings by policy.

Protocol DTO policy (maintainer, 2026-07-31): the DAP/JsonRpc surfaces are
deliberately SPEC-COMPLETE. No spec message is a dead-code candidate. Fix
pass follow-up: state this in the dap-protocol README; audit which DTOs are
custom additions (only those are ever trimmable).

## Findings

- **[fix] `e.printStackTrace()` in production code paths**:
  `dap-protocol .../client/DapClient.java:189`,
  `vshaxe .../adapter/HxcppDebugAdapter.java:177`,
  `vshaxe .../jsonrpc/JsonRpcClient.java:134` — raw stderr from library
  code; route through each module's `[dap]`-style diagnostics instead.
- **[fix] Wrapping constructor-in-call**: `eval .../EvalConnection.java:165`
  (`completeExceptionally(new EvalProtocolException(...)`) and
  `eval .../EvalProtocol.java:85` (`frames.add(new EvalStackFrame(...)`) —
  payload extraction per the structural rule.
- **[discuss] Name collision**: hashlink-debug-adapter's TEST tree contains
  `HlExecutableResolver` while the root plugin has
  `runner/debugger/hashlink/HlExecutableResolver` — same name, two
  implementations of "find the hl binary". Verify whether the test one can
  reuse the production one (test-jar dependency) or rename to say what
  differs (e.g. MatrixHlResolver).
- Phase-0 orphan flags inside these modules (protocol DTOs, compat-matrix
  entry points, live-test bases) are all explained: spec-completeness,
  gradle-invoked mains, JUnit discovery. No action.

Chunks 5-8 complete: 523/523.
