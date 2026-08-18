# Branch review instructions (develop...HEAD)

You are reviewing one chunk of this branch's changes against `develop`.
Work from the worktree root. REPORT ONLY — do not edit any source file.

## Setup

1. Read `CLAUDE.md` — specifically the **Code style**, **Code review** and
   **Test code** sections. They are the checklist; apply them literally.
2. Your chunk file (path given in your task prompt) lists one file per line:
   - `A path` — added file: review the whole file.
   - `M path` / `R path` — modified/renamed: review
     `git diff develop...HEAD -- <path>` AND read enough of the current file
     to judge the change in context. Only the CHANGED code and its immediate
     surroundings are in scope — do not report on untouched legacy code.

## What to check (from CLAUDE.md, condensed)

- Names describe what the code does NOW; no class doing several jobs;
  package direction (model/buildtools never import UI/toolwindow).
- Structural style: 3+ call chains split; no multi-line values inside calls
  (extract named locals/payloads); repeated expressions become one local;
  small statement heads; per-type switches that must grow → interface method;
  text blocks for multi-line strings; wrapping Map.of one pair per line;
  non-trivial lambdas named; regex comments; blank lines separate groups;
  no pass-through wrapper methods; user-visible text in bundles.
- TODOs in changed code: already solved / needs solving / legitimately
  deferred (must say WHAT is deferred).
- Dead code: zero-reference classes/methods among the ADDED files (check
  `::method` refs and framework callbacks/registrations before claiming);
  consumer-less API without a TODO; commented-out code; bundle keys with no
  caller.
- Registrations: every class named in plugin.xml or other XML you touch
  resolves; files cited by comments exist.
- Duplication: same predicate/constant/expression in 2+ places; a helper the
  JDK/platform/our utils already provide.
- Platform API: flag any `@Deprecated` or `@ApiStatus.Internal` platform
  call the branch introduces (check the annotation if unsure; if you cannot
  verify, flag as a QUESTION rather than asserting).
- Test code rules (for test chunks): member order, @DisplayName patterns,
  factories in the test base, assertion conditions as named facts.

## Output

Write your findings to `doc/code-review-branch/findings/<chunk-name>.md`
(same base name as your chunk file). Format:

```
# <chunk-name>

### <fix-now | should-fix | minor | question> — <file>:<line>
One concise paragraph: what is wrong, why it violates the checklist, and the
concrete fix. Quote the offending line(s) briefly when short.
```

Order findings most severe first. If nothing survives scrutiny, write the
file with exactly `No findings.` under the title. Do not pad — absence of
findings is a valid result; invented nitpicks are noise. Your final response
should be only a one-line summary: `<chunk-name>: N findings (X fix-now)`.
