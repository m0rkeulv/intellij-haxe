# Code review: current state of everything changed since 1.8.8

Assignment: review the CURRENT working-tree state of every source file touched
by commits after baseline `6da9c9e2abed4f9dad4ff632a0cc0700231542d6` (the
1.8.8 release point; upstream tags are not present locally), against the
CLAUDE.md Code review checklist.

Parameters (confirmed 2026-07-31):
- **Report only** — no code changes in this pass; fixes are a separate pass
  after the findings are reviewed.
- **Nothing excluded** — vendored code (hxcpp-debugger-protocol-legacy) and
  the recently-reviewed v2/display-protocol areas are all in scope.
- **Tests fully in scope**, including the test-code rules retrofit
  (@DisplayName, member order, request factories) — reported, not applied.
- `src/main/gen` excluded (generated, never hand-edited).

## Files

- `files.txt` — the 983 in-scope files (source of truth).
- `progress.tsv` — per-file status: `pending` / `done` / `na`.
- `findings-00-automated.md` — results of the mechanical sweeps.
- `findings-<NN>-<area>.md` — per-area findings from the manual chunks.

## Chunk order (by value, core first)

1. main/model + main/lang (resolver, evaluator, PSI — 34 files)
2. main/ide + main/util + main/haxelib + main/config + root (45)
3. main/runner (92)
4. main/v2 + main/kotlin (102 — recently reviewed; light re-pass)
5. debuggers/dap-protocol (161)
6. debuggers/hashlink-debug-adapter (180, java + haxe)
7. debuggers/intellij-hxcpp-debugger + vshaxe adapter + eval + browser +
   compat-matrix (110)
8. debuggers/hxcpp-debugger-protocol-legacy (72, vendored — expect mostly
   "leave as vendored" findings)
9. tools/LimeProjectParser + jps-plugin + common + display-protocol (~30)
10. tests (155) including the test-rules retrofit report

Each chunk: read files, apply the CLAUDE.md checklist, write findings with
file:line references, mark progress.tsv. Findings are graded:
- **[fix]** mechanical, safe to apply later without discussion
- **[discuss]** judgment call needing the maintainer's decision
- **[vendored]** would fix in our code; flagged only for awareness here

## STATUS: COMPLETE (2026-07-31)

All 983 files processed. Findings in findings-00 through findings-08.
Depth calibration: pre-rules code read file-by-file; rules-era modules
(v2, debuggers, display-protocol — built and reviewed under CLAUDE.md)
pattern-swept + spot-read; vendored code marked [vendored] by policy.
No code was changed in this pass (report-only). The fix pass is separate
and starts from the [fix]/[discuss] items in the findings files.
