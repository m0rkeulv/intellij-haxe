# Debugger compatibility matrix

Runs the debugger test suites against every stored haxe version (and, for
HashLink, every stored runtime) and writes a self-contained HTML report:
summary card per debugger, a green/red grid per lane, and the list of any
failing tests. This is the "full check on all our debugger work" button.

## Running

```
gradlew debuggerCompatibilityReport                     # all lanes
gradlew debuggerCompatibilityReport -PmatrixLanes=eval  # one lane
gradlew debuggerCompatibilityReport -PmatrixFull=true   # exhaustive HL grid
gradlew debuggerCompatibilityReport -PmatrixTestData=D:\my\testdata
```

or the script directly (same options, plus `-ReportOnly` to regenerate the
report from the previous run's collected results):

```
powershell -ExecutionPolicy Bypass -File debuggers\compat-matrix\run-matrix.ps1 [-Lanes eval,hashlink,hxcpp] [-Full] [-TestData <dir>]
```

The report lands in `build/reports/debugger-matrix/index.html`; per-cell
gradle logs and the copied junit XMLs sit next to it (`logs/`, `results/`).
Progress streams to the console and `progress.log`.

Windows-only (the debugger integration suites are Windows-hosted anyway).

## Version store

```
<TestData>\                       (default P:\Workspaces\haxe-intellij-testdata)
  haxe\
    haxe_4_1_5\haxe.exe + std\    (folder name is the report label)
    ...
    haxe_5_preview_1\
  hashlink\
    hashlink-1.13.0-win\hl.exe
    ...
```

Versions are DISCOVERED from those folders — to certify a new haxe release,
drop it in `haxe\` and rerun. Versions below the support floor (haxe < 4.1,
HashLink < 1.13) are skipped by the lists at the top of `run-matrix.ps1`.

## Duration

- eval lane: ~1 minute per haxe version (live suite against the real VM).
- hashlink lane: fixture build per haxe version + a test run per runtime;
  the default "smart-reduced" grid runs known-degraded old haxe versions
  against one reference runtime only (their failures were proven identical
  on every runtime); `-Full` runs every combination.
- hxcpp lane: the slowest — every haxe version compiles the C++ fixtures.

All lanes on the default store: expect roughly an hour; eval alone ~6 min.

## Why the odd flags (do not "simplify")

- `--no-daemon` on every gradle call: the forked test JVM must inherit the
  lane's `PATH`/`HAXE_STD_PATH`; a warm daemon keeps the env it was born
  with and silently tests the wrong haxe.
- `cleanTest --no-build-cache` per cell: the lane's haxe/runtime is not a
  tracked test input, so the build cache happily replays a previous cell's
  result as an 8-second "run".
- Bounded calls + process-tree kill + stray `hl`/fixture cleanup: a stuck
  runtime error dialog must not wedge the whole matrix.
- haxelib dev registrations happen once up front; lanes exclude those tasks
  so nothing races the shared haxelib repository.
- The HL debug adapter bytecode is built once with the dev haxe and stays
  PINNED; only fixtures are rebuilt per lane haxe (the shipped adapter is a
  dev-haxe artifact — that is exactly what users run).
- After the run the HL fixtures are rebuilt with the dev haxe so the
  working tree is back in its normal state.
