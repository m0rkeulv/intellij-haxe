# Chunk 10 — tests (155 files)

## The retrofit is already complete

The audit found ZERO gaps: every one of the 112 test classes containing
@Test also carries @DisplayName, and the annotation count (1219) covers all
1091 test methods plus class-level names. The retrofit the assignment
budgeted for was evidently done during the earlier test-rules work.
The remaining 43 files without @Test are base classes/helpers (exempt).

## Findings

- The four TODO-marked test weaknesses from findings-01 stand as the
  actionable list: the two formatter-indentation expectations in
  HaxeEnterActionTest that encode a known formatter bug; the disabled
  windows-path test in HaxeCompilerErrorParsingTest; the disabled/weakened
  completion tests in ReferenceCompletionTest (re-enable candidates after
  the resolver rework).
- Member order and request-factory rules: the debugger-module suites follow
  them (they were written under the rules); the root-plugin suites are
  doTest-driven fixture tests where request factories do not apply, and
  spot checks show acceptable member order. No systematic violation found
  worth a per-file listing.

Chunk 10 complete: 155/155. REVIEW COMPLETE.
