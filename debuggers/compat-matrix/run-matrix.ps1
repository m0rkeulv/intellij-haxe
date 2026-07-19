<#
  Debugger compatibility matrix runner.

  Runs the debugger test suites against every stored haxe version (and, for
  HashLink, every stored runtime), then writes a self-contained HTML report.
  Distilled from the throwaway matrix runners that certified the version
  support policy; every non-obvious flag below is a lesson from those runs.

  Usage (or via `gradlew debuggerCompatibilityReport`):
    run-matrix.ps1                        # all lanes, smart-reduced HL grid
    run-matrix.ps1 -Lanes eval            # just the eval debugger
    run-matrix.ps1 -Full                  # exhaustive HL grid (every runtime)
    run-matrix.ps1 -ReportOnly            # regenerate the report from the
                                          # previous run's collected results

  Version store layout (see README.md):
    <TestData>\haxe\<version>\haxe.exe + std\
    <TestData>\hashlink\<runtime>\hl.exe

  LESSONS ENCODED HERE (do not "simplify" these away):
   - Every gradle call is --no-daemon so the forked test JVM inherits THIS
     process's PATH/HAXE_STD_PATH (a daemon keeps the env it was born with).
   - cleanTest + --no-build-cache per cell: the build cache would replay a
     PRIOR cell's test result (runtime/env are not tracked test inputs).
   - One haxe version at a time per module (never two compilers into the
     same module's build dir), sequential lanes (CPU contention caused
     flaky socket resets when lanes ran in parallel on one machine).
   - Bounded gradle calls with process-TREE kill + stray debuggee cleanup:
     a stuck hl.exe error dialog must not wedge the whole matrix.
   - haxelib dev registrations happen ONCE up front; lanes exclude those
     tasks so nothing races the shared haxelib repository.
   - The HL debug adapter bytecode stays PINNED (built once with the dev
     haxe); only FIXTURES are rebuilt per lane haxe.
#>
param(
    [string]$TestData = "P:\Workspaces\haxe-intellij-testdata",
    [string]$Lanes = "eval,hashlink,hxcpp",
    [string]$Out = "",
    [switch]$Full,
    [switch]$ReportOnly
)

$ErrorActionPreference = "Continue"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$gradlew = "$root\gradlew.bat"
if ($Out -eq "") { $Out = "$root\build\reports\debugger-matrix" }
$laneList = $Lanes.Split(",") | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ }

# --- support policy: versions below the line are skipped even if stored ---
$unsupportedHaxe = @("haxe_4_0_5")                # min supported: haxe 4.1
$unsupportedHl   = @("hashlink-1.9.0-win", "hashlink-1.10.0-win",
                     "hashlink-1.11.0-win", "hashlink-1.12.0-win") # min: 1.13
# haxe versions with known fixture-level degradation on HL: identical failures
# on every runtime, so (unless -Full) one reference runtime characterizes them
$degradedHaxeOnHl = @("haxe_4_2_5", "haxe_4_1_5")
$referenceRuntime = "hashlink-1.15.0-win"

$evalModule = "$root\debuggers\eval-debugger"
$hlModule   = "$root\debuggers\hashlink-debug-adapter"
$hxModule   = "$root\debuggers\intellij-hxcpp-debugger"
$hlFixtureTasks = @("buildTestFixture","buildThreadsFixture","buildSpinFixture","buildUncaughtFixture","buildVmFixture","buildStackTraceFixture","buildTypedThrowFixture")
$hlFixtureFiles = @{ buildTestFixture="test-fixture.hl"; buildThreadsFixture="threads-fixture.hl"; buildSpinFixture="spin-fixture.hl"; buildUncaughtFixture="uncaught-fixture.hl"; buildVmFixture="vm-fixture.hl"; buildStackTraceFixture="stacktrace-fixture.hl"; buildTypedThrowFixture="typedthrow-fixture.hl" }
$hxFixtureTasks = @{ buildHxcppFixtureFixture="hxcpp\fixture\Main-debug.exe"; buildHxcppFixtureExFixture="hxcpp\fixture-ex\MainEx-debug.exe" }
$excludeHaxelib = @("-x","installFormatHaxelib","-x","registerDapProtocolHaxelib","-x","registerServerHaxelib","-x","installHscript")

New-Item -ItemType Directory -Force "$Out\logs" | Out-Null
New-Item -ItemType Directory -Force "$Out\results" | Out-Null
$progress = "$Out\progress.log"
$script:origPath = $env:PATH

function Now { (Get-Date).ToString("HH:mm:ss") }

function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Now), $msg
    Write-Host $line
    # FileShare.ReadWrite append with retry: a reader tailing the log must
    # never make a progress write throw (Windows lock semantics)
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $fs = [System.IO.File]::Open($progress, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
            $bytes = [System.Text.Encoding]::UTF8.GetBytes("$line`r`n")
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Close()
            return
        } catch { Start-Sleep -Milliseconds 100 }
    }
}

function Kill-Strays {
    foreach ($n in @("hl","haxe","Main-debug","MainEx-debug")) {
        try { Get-Process -Name $n -ErrorAction Stop | Stop-Process -Force -ErrorAction SilentlyContinue } catch {}
    }
}

function Run-Gradle([string[]]$tasks, [string]$logFile, [int]$timeoutSec) {
    $gargs = @("-p", $root) + $tasks + @("--no-daemon", "--console=plain")
    $p = Start-Process -FilePath $gradlew -ArgumentList $gargs -WorkingDirectory $root `
        -PassThru -NoNewWindow -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"
    $null = $p.Handle
    if (-not $p.WaitForExit($timeoutSec * 1000)) {
        try { & taskkill /PID $p.Id /T /F 2>$null | Out-Null } catch {}
        Kill-Strays
        return "timeout"
    }
    if ($p.ExitCode -eq 0) { return "ok" } else { return "fail" }
}

function Set-HaxeEnv([string]$haxe) {
    $dir = "$TestData\haxe\$haxe"
    $env:PATH = "$dir;$script:origPath"
    $env:HAXE_STD_PATH = "$dir\std"
}

# Parses every junit XML in a directory into per-class summaries.
function Parse-Results([string]$dir) {
    $classes = @()
    foreach ($f in Get-ChildItem "$dir\*.xml" -ErrorAction SilentlyContinue) {
        try {
            [xml]$d = Get-Content $f.FullName
            $ts = $d.testsuite
            $failed = @()
            foreach ($tc in $ts.testcase) {
                if ($tc.failure) { $failed += @{ test = $tc.name; message = "$($tc.failure.message)" } }
            }
            $classes += @{
                name = ($ts.name -replace ".*\.", ""); tests = [int]$ts.tests
                failures = [int]$ts.failures; errors = [int]$ts.errors; skipped = [int]$ts.skipped
                failed = $failed
            }
        } catch {}
    }
    return $classes
}

# Copies a module's junit XMLs into results\<cellName>\ (the raw evidence a
# later -ReportOnly regenerates from) and returns the per-class summaries.
function Collect-Cell([string]$resultsDir, [string]$cellName) {
    $dest = "$Out\results\$cellName"
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Force $dest | Out-Null
    if (Test-Path $resultsDir) {
        Get-ChildItem "$resultsDir\*.xml" -ErrorAction SilentlyContinue | Copy-Item -Destination $dest -Force
    }
    return Parse-Results $dest
}

function Discover([string]$sub, [string[]]$skip) {
    if (-not (Test-Path "$TestData\$sub")) { return @() }
    Get-ChildItem "$TestData\$sub" -Directory | ForEach-Object { $_.Name } |
        Where-Object { $skip -notcontains $_ } | Sort-Object
}

$haxeVersions = Discover "haxe" $unsupportedHaxe
$hlRuntimes = Discover "hashlink" $unsupportedHl
$cells = New-Object System.Collections.ArrayList

function Add-Cell($lane, $haxe, $runtime, $status, $classes, $seconds) {
    $null = $cells.Add(@{ lane = $lane; haxe = $haxe; runtime = $runtime
                          status = $status; classes = $classes; seconds = [math]::Round($seconds) })
}

# ------------------------------------------------------------------ eval lane
function Run-EvalLane {
    Log "EVAL LANE ($($haxeVersions -join ', '))"
    foreach ($haxe in $haxeVersions) {
        Set-HaxeEnv $haxe
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $st = Run-Gradle @(":debuggers:eval-debugger:cleanTest",":debuggers:eval-debugger:test","-PdebuggerTests=true","--no-build-cache","--continue") "$Out\logs\eval-$haxe.log" 900
        Kill-Strays
        $classes = Collect-Cell "$evalModule\build\test-results\test" "eval_$haxe"
        Add-Cell "eval" $haxe $null $st $classes $sw.Elapsed.TotalSeconds
        $fails = ($classes | ForEach-Object { $_.failures + $_.errors } | Measure-Object -Sum).Sum
        Log ("  eval {0,-18} : {1,-7} classes={2} failures={3} ({4:n0}s)" -f $haxe, $st, $classes.Count, $fails, $sw.Elapsed.TotalSeconds)
    }
}

# ------------------------------------------------------------------ hxcpp lane
function Run-HxcppLane {
    Log "HXCPP LANE ($($haxeVersions -join ', '))"
    foreach ($haxe in $haxeVersions) {
        Set-HaxeEnv $haxe
        Remove-Item "$hxModule\build\hxcpp" -Recurse -Force -ErrorAction SilentlyContinue
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $buildTasks = @($hxFixtureTasks.Keys | ForEach-Object { ":debuggers:intellij-hxcpp-debugger:$_" }) + $excludeHaxelib + @("--continue")
        Run-Gradle $buildTasks "$Out\logs\hxcpp-$haxe-build.log" 1200 | Out-Null
        $missing = @($hxFixtureTasks.Keys | Where-Object { -not (Test-Path "$hxModule\build\$($hxFixtureTasks[$_])") })
        if ($missing.Count -eq $hxFixtureTasks.Count) {
            Add-Cell "hxcpp" $haxe $null "compile-fail" @() $sw.Elapsed.TotalSeconds
            Log ("  hxcpp {0,-17} : COMPILE-FAIL (no fixture built)" -f $haxe)
            continue
        }
        $excludeMissing = @(); foreach ($t in $missing) { $excludeMissing += @("-x", $t) }
        $testTasks = @(":debuggers:intellij-hxcpp-debugger:cleanTest",":debuggers:intellij-hxcpp-debugger:test","--no-build-cache") + $excludeHaxelib + $excludeMissing + @("--continue")
        $st = Run-Gradle $testTasks "$Out\logs\hxcpp-$haxe-test.log" 1500
        Kill-Strays
        $classes = Collect-Cell "$hxModule\build\test-results\test" "hxcpp_$haxe"
        Add-Cell "hxcpp" $haxe $null $st $classes $sw.Elapsed.TotalSeconds
        $fails = ($classes | ForEach-Object { $_.failures + $_.errors } | Measure-Object -Sum).Sum
        Log ("  hxcpp {0,-17} : {1,-7} classes={2} failures={3} missingFixtures={4} ({5:n0}s)" -f $haxe, $st, $classes.Count, $fails, $missing.Count, $sw.Elapsed.TotalSeconds)
    }
}

# --------------------------------------------------------------- hashlink lane
function Run-HashlinkLane {
    $adapterHl = "$hlModule\build\hl\hl-debug-adapter.hl"
    if (-not (Test-Path $adapterHl)) {
        Log "  building the (pinned) HL debug adapter with the dev haxe"
        $env:PATH = $script:origPath; $env:HAXE_STD_PATH = ""
        Run-Gradle @(":debuggers:hashlink-debug-adapter:buildDebugAdapter") "$Out\logs\hl-adapter.log" 600 | Out-Null
    }
    $pinned = (Get-Item $adapterHl -ErrorAction SilentlyContinue).LastWriteTime
    Log "HL LANE ($($haxeVersions -join ', ') x $($hlRuntimes -join ', '); adapter pinned @ $pinned; $(if($Full){'FULL'}else{'smart-reduced'}))"
    foreach ($haxe in $haxeVersions) {
        Set-HaxeEnv $haxe
        foreach ($f in $hlFixtureFiles.Values) { Remove-Item "$hlModule\build\hl\$f" -Force -ErrorAction SilentlyContinue }
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $buildTasks = @($hlFixtureTasks | ForEach-Object { ":debuggers:hashlink-debug-adapter:$_" }) + $excludeHaxelib + @("--continue")
        Run-Gradle $buildTasks "$Out\logs\hl-$haxe-build.log" 600 | Out-Null
        $missing = @($hlFixtureTasks | Where-Object { -not (Test-Path "$hlModule\build\hl\$($hlFixtureFiles[$_])") })
        if ($missing -contains "buildTestFixture") {
            foreach ($hl in $hlRuntimes) { Add-Cell "hashlink" $haxe $hl "compile-fail" @() $sw.Elapsed.TotalSeconds }
            Log ("  hl {0,-20} : COMPILE-FAIL (core fixture missing)" -f $haxe)
            continue
        }
        $runtimes = if ($Full -or ($degradedHaxeOnHl -notcontains $haxe)) { $hlRuntimes } else { @($referenceRuntime) }
        $excludeMissing = @(); foreach ($t in $missing) { $excludeMissing += @("-x", $t) }
        $excludeBuilds = @("-x","buildDebugAdapter") + ($hlFixtureTasks | ForEach-Object { @("-x", $_) })
        foreach ($hl in $runtimes) {
            $hlExe = "$TestData\hashlink\$hl\hl.exe"
            if (-not (Test-Path $hlExe)) { Log "  hl runtime $hl missing hl.exe - skipped"; continue }
            $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
            $testTasks = @(":debuggers:hashlink-debug-adapter:cleanTest",":debuggers:hashlink-debug-adapter:test","-PhashlinkBin=$hlExe","--no-build-cache") + $excludeHaxelib + $excludeBuilds + $excludeMissing + @("--continue")
            $st = Run-Gradle $testTasks "$Out\logs\hl-$haxe-$hl.log" 1500
            Kill-Strays
            $classes = Collect-Cell "$hlModule\build\test-results\test" "hl_${haxe}_$hl"
            Add-Cell "hashlink" $haxe $hl $st $classes $sw2.Elapsed.TotalSeconds
            $fails = ($classes | ForEach-Object { $_.failures + $_.errors } | Measure-Object -Sum).Sum
            Log ("  hl {0,-18} x {1,-22} : {2,-7} failures={3} ({4:n0}s)" -f $haxe, $hl, $st, $fails, $sw2.Elapsed.TotalSeconds)
        }
        if ((Get-Item $adapterHl).LastWriteTime -ne $pinned) { Log "  WARNING: pinned adapter was rebuilt during $haxe" }
    }
}

# ------------------------------------------------------------------- report
function Write-Report {
    $meta = @{ generated = (Get-Date).ToString("yyyy-MM-dd HH:mm")
               testData = $TestData; full = [bool]$Full
               haxeVersions = @($haxeVersions); hlRuntimes = @($hlRuntimes) }
    $payload = @{ meta = $meta; cells = @($cells) } | ConvertTo-Json -Depth 8 -Compress
    $payload = $payload.Replace("</", "<\/")
    # -Encoding UTF8 is load-bearing: the template is BOM-less UTF-8 and
    # Windows PowerShell 5.1 would otherwise read it as ANSI (mojibake)
    $template = Get-Content "$PSScriptRoot\report-template.html" -Raw -Encoding UTF8
    $html = $template.Replace("/*__DATA__*/null", $payload)
    Set-Content -Path "$Out\index.html" -Value $html -Encoding utf8
    Log "report: $Out\index.html"
}

# --------------------------------------------------------------- entry point
if ($ReportOnly) {
    # rebuild the cell list from the collected result copies of a prior run
    foreach ($dir in Get-ChildItem "$Out\results" -Directory -ErrorAction SilentlyContinue) {
        $parts = $dir.Name.Split("_", 2)
        $lane = $parts[0]; $rest = $parts[1]
        $haxe = $rest; $runtime = $null
        if ($lane -eq "hl") { $lane = "hashlink"
            $ix = $rest.IndexOf("_hashlink-"); if ($ix -ge 0) { $haxe = $rest.Substring(0, $ix); $runtime = $rest.Substring($ix + 1) } }
        $classes = Parse-Results $dir.FullName
        Add-Cell $lane $haxe $runtime "ok" $classes 0
    }
    Write-Report
    exit 0
}

Log "matrix start: lanes=$($laneList -join '+') haxe=[$($haxeVersions -join ', ')] hl=[$($hlRuntimes -join ', ')]"
if ($haxeVersions.Count -eq 0) { Log "no haxe versions found under $TestData\haxe - aborting"; exit 1 }

if (($laneList -contains "hashlink") -or ($laneList -contains "hxcpp")) {
    # haxelib dev registrations ONCE, with the dev haxe (lanes exclude them)
    Run-Gradle @(":debuggers:hashlink-debug-adapter:registerDapProtocolHaxelib",":debuggers:intellij-hxcpp-debugger:registerServerHaxelib") "$Out\logs\preflight.log" 300 | Out-Null
}

if ($laneList -contains "eval")     { Run-EvalLane }
if ($laneList -contains "hashlink") { Run-HashlinkLane }
if ($laneList -contains "hxcpp")    { Run-HxcppLane }

# restore: dev haxe back on PATH, dev-built HL fixtures back in place
$env:PATH = $script:origPath; $env:HAXE_STD_PATH = ""
if ($laneList -contains "hashlink") {
    foreach ($f in $hlFixtureFiles.Values) { Remove-Item "$hlModule\build\hl\$f" -Force -ErrorAction SilentlyContinue }
    Run-Gradle (@($hlFixtureTasks | ForEach-Object { ":debuggers:hashlink-debug-adapter:$_" }) + $excludeHaxelib + @("--continue")) "$Out\logs\restore-hl-fixtures.log" 600 | Out-Null
}
if ($laneList -contains "hxcpp") {
    Remove-Item "$hxModule\build\hxcpp" -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Report
Log "matrix done"
