# Chunk 3 — main/runner (92 files)

Calibration: the dap/ide, hashlink, browser, eval, interp, hxcpp and
exceptions families are rules-era code (built and repeatedly reviewed in the
debugger sessions with CLAUDE.md in force). They were pattern-swept (debug
log overrides, mutable statics, anonymous listeners, wrapping constructor
calls, empty javadoc, instanceof chains) rather than re-read line by line;
the sweep came back essentially clean. The pre-rules candidates (flash
family, HaxeDebugProcess, support utils, run-configuration type) were read —
and turned out to have been modernized during the branch as well
(FlashRunConfiguration now extends DapRunConfigurationBase, Math.clamp,
computeBlocking, bundled messages).

## Findings

- **[fix] `DapDebuggerEvaluator:46` and `DapValue:82`** — the two wrapping
  `new DapValue(...)` constructor-in-call sites; payload-extraction per the
  structural rule.
- **[fix] `HaxeRunConfigurationType`** — interface methods (getDisplayName,
  getConfigurationTypeDescription, getIcon, getId,
  getConfigurationFactories) missing @Override.
- **[discuss] `HaxeDebuggerSupportUtils.getContextElement`** — the do/while
  scan calls `element.getTextRange()` without a null check on
  `findElementAt` (possible at end-of-file boundaries); add a null break.
- TODO at `LegacyHxcppDebugProcess:200` (runToPosition unsupported) already
  inventoried — legit protocol limitation.
- The two orphaned smart-step handlers (`DapSmartStepIntoHandler`,
  `HashLinkSmartStepIntoHandler`) remain the known [discuss] from phase 0:
  wire in or delete.

Everything else conforms. Chunk 3 complete: 92/92.
