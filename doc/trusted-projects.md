# Trusted-project gating for process execution

IntelliJ asks on open whether the user trusts a project. In an untrusted
project ("safe mode") the plugin must not execute any code that comes from
the project itself. This doc maps every execution site, classifies its risk,
and lays out the gating plan.

## Platform API (2026.2, verified against sources)

| API | Status | Use |
|---|---|---|
| `com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted(Project)` | public, `@JvmStatic` | the gate check |
| `TrustedProjects.setProjectTrusted(Project, boolean)` | public, `@JvmStatic` | grant trust from our own confirm dialog |
| `TrustedProjectsListener` + `onceWhenProjectTrusted(Disposable, Consumer)` | `@ApiStatus.Experimental` (not Internal) | re-hydrate everything when trust is granted |
| `TrustedProjectsDialog` | `@ApiStatus.Internal` — **forbidden** | build our own via `MessageDialogBuilder.yesNo` + `setProjectTrusted` |

Notes:
- In headless/unit-test mode the check auto-trusts (`idea.trust.headless.disabled`
  defaults true) — the test suite is unaffected. An EXPLICIT
  `setProjectTrusted(project, false)` still wins over the headless auto-trust,
  so gating is unit-testable.
- Safe mode already disables Run/Debug configurations platform-side; our
  own non-run-config execution paths get no such protection.

## Execution map

### Executes PROJECT-AUTHORED code, fires automatically — must gate

| # | Trigger | Chain (entry → spawn) | What runs |
|---|---|---|---|
| G1 | Haxe tool window build (auto at startup when restored open; re-fired by module/build/server listeners) | `HaxeToolWindowPanel.refreshTree` → `HaxeToolWindowModelBuilder` → `HaxeLimeProjectInfoService.getCachedOrSchedule` / `HaxeNmeProjectInfoService.getCachedOrSchedule` | `.hxp`: LimeProjectParser compiles and **runs the user's hxp script** (`haxe --run`); fallback `haxelib run lime\|openfl display` / `haxelib run nme prepare` execute the tool **from the project's local `.haxelib`** and evaluate the project build description |
| G2 | Parsing/indexing/highlighting any `.hx` file (define context for `#if`) | `HaxeConditionalExpression` / indexes / `HaxeFastColorAnnotator` → `HaxeDefineDetectionManager` → `HaxeDefineContextService` → `HaxeLimeProjectInfoService` | same as G1 — reachable by merely opening a Haxe file |
| G3 | Editor resolve fallback (default ON via `completionMode = IDE_AND_COMPILER`) | `HaxeResolver` → `HaxeCompilerResolveService` → `HaxeCompilerDisplayService.connectFor` → `HaxeCompilationServerManager.ensureRunning` + `--no-output` compile | starts `haxe --wait`, full project compile ⇒ **build/init macros run**; lime display / nme prepare for those contexts |
| G4 | Index-facade lookups → compiler type catalog (default ON) | `Haxe*CompilerIndex` → `HaxeCompilerTypeCatalogService` → `connectFor` → `ensureRunning` + warm-up compile | same as G3 |
| G5 | Compiler diagnostics / metadata / usage services (default OFF) | `HaxeCompilerDiagnosticsAnnotator`, `HaxeCompilerMetadataService`, `HaxeCompilerUsageService` → `connectFor` | server compile ⇒ macros |
| G6 | Build-file change → auto-reload | `HaxeBuildFilesProjectAware.reloadProject` → `HaxeProjectSync` → `HaxeLibrarySync` → lime/hxp evaluation + `haxelib path` per dep | lime/hxp evaluation leg |
| G7 | Legacy V1 open (only projects with `HAXE_MODULE` modules) | `HaxelibProjectStartActivity` → `HaxelibProjectUpdater.syncOpenFLModule` | `haxelib run openfl display flash` |

### Executes project code, but only on a USER GESTURE — confirm dialog

| # | Action | What runs |
|---|---|---|
| U1 | Tool window "Execute command" / action-row double-click (`HaxeCommandRunner`) | arbitrary command from the project's build config |
| U2 | IDE Build Project/Module (`HaxeProjectTaskRunner`) and the before-run compile task (`HaxeActionBeforeRunTaskProvider`) | project compile commands ⇒ macros |
| U3 | Compilation-server console Start/Restart buttons | `haxe --wait` + later compiles |
| U4 | Generated-code preview navigation (`HaxeGeneratedDumpService.ensureDumps`) | full `-D dump=pretty` compile ⇒ macros |
| U5 | Run/Debug configurations (all runners), test runs | platform safe mode already blocks these; no extra gate needed, but the launch-time `lime display` calls sit behind them anyway |

### SDK-tool reads only — NOT gated (deliberately)

`haxelib list/search/info/path/config`, `haxe -help/--help-metas/--help-defines`
(Haxelib Explorer population, completion caches, SDK probe, `haxelib path`
resolution in library sync). These execute the user's configured SDK
toolchain, not code from the project; gating them would gut the explorer and
completion for no security gain. `haxelib install/remove/set` (explorer
context menu, install-missing action) likewise only drives the SDK tool and
stays ungated. The line is: anything that can run `haxelib run <tool>`, an
`.hxp` script, or a compile (macros) is gated; plain SDK-tool invocations are
not.

## Design

### 1. `HaxeProjectTrust` helper (new, `v2/buildtools`)

```java
public final class HaxeProjectTrust {
  /** The bare check, usable from any thread. */
  public static boolean isTrusted(Project project);

  /** For AUTOMATIC paths: false when untrusted; the FIRST block per project
   *  session raises one warning notification ("compiler-backed features are
   *  disabled in an untrusted project") with a Trust Project action.
   *  Subsequent blocks are silent. */
  public static boolean checkForBackgroundEvaluation(Project project);

  /** For USER ACTIONS: true when trusted; otherwise shows a yes/no dialog
   *  ("<action> executes code from this project — trust this project?"),
   *  grants trust via TrustedProjects.setProjectTrusted on yes. EDT only. */
  public static boolean confirmForAction(Project project, @Nls String actionName);
}
```

The once-per-session dedupe lives in a small project service holding an
`AtomicBoolean`. The notification goes through the existing
`HaxeCommandNotifications` channel; strings in `HaxeBundle`
(`haxe.trust.*` keys).

### 2. Gate placement — choke points, not leaves

Gating `HaxeProcessUtil`/`HaxelibCommandUtils` wholesale would break the
ungated SDK reads, so the checks go into the semantic entry points:

| Gate | Covers | Untrusted behaviour |
|---|---|---|
| `HaxeLimeProjectInfoService.getCachedOrSchedule` (both the parser-jar and display legs; parser jar still runs hxp) | G1, G2, G6 lime/hxp/nme leg | return no info — consumers already handle "not yet evaluated" and fall back to declared XML values |
| `HaxeNmeProjectInfoService.getCachedOrSchedule` | G1 nme | same |
| `HaxeCompilationServerManager.ensureRunning` | G3, G4, G5 | report not-running; display services already treat an unavailable server as cache-miss |
| `HaxeLibrarySync.sync` lime-evaluation leg (via the info-service gate) + skip nothing else — `haxelib path` stays | G6 | sync still resolves declared libs |
| `HaxelibProjectUpdater.syncOpenFLModule` display call | G7 | skip; classpath from declared data only |
| `HaxeGeneratedDumpService.ensureDumps` | U4 | `confirmForAction` |
| `HaxeCommandRunner.run` call sites (tool window execute/compile actions) | U1 | `confirmForAction` |
| `HaxeProjectTaskRunner` + `HaxeActionBeforeRunTaskProvider` | U2 | `confirmForAction` (EDT entry) / silent fail with error message in build output (headless entry) |
| Server console Start/Restart actions | U3 | `confirmForAction` |

`.hxp` specifics: the LimeProjectParser jar itself is plugin code, but its
`HxpEvaluator` compiles and runs the project's script — so for `.hxp` files
the WHOLE info service is gated, not just the display fallback. For
`project.xml` the jar only shells out to `haxelib path` (SDK read), but the
service is gated as one unit anyway: its results exist to feed compiler-level
evaluation, and split-gating buys nothing.

### 3. UX — exactly one passive warning, active errors on gestures

- **Startup / background**: the first blocked automatic evaluation raises ONE
  warning notification per project session; everything after is silent.
  Features degrade to declared-only data (tool window shows the build file as
  written, resolve/completion run IDE-only, no server).
- **Tool window hint**: the Haxe tool window's environment row shows
  "(project not trusted — compiler evaluation disabled)" so the degraded
  state is discoverable after the notification is gone. Same one-liner in the
  compilation-server console window instead of the start button doing nothing.
- **User gestures**: the confirm dialog IS the error surface — deny keeps the
  action cancelled, no extra balloon. Yes grants trust project-wide via the
  platform API, so the IDE's own banners disappear too.
- **On trust granted** (`onceWhenProjectTrusted` registered from
  `HaxeV2ProjectActivity` with the project as disposable):
  `HaxeProjectSync.sync(project)` + tool window refresh + restart the
  highlighting daemon — the same re-hydration the reload icon does — so the
  project springs to life without a reopen.

### 4. Test plan

`TrustedProjects.setProjectTrusted(project, false)` (explicit state beats the
headless auto-trust) in unit tests, then:
- info services return empty and spawn nothing (assert no process via a
  test-visible counter or by SDK-less fixture),
- `ensureRunning` refuses to start,
- one notification fired per session, not N,
- `setProjectTrusted(project, true)` + listener → sync re-scheduled.
Existing suite stays green because tests are auto-trusted by default.

### 5. Out of scope

- The Haxelib Explorer and completion caches stay ungated (SDK reads).
- Debugger modules: reachable only through run configs, which safe mode
  blocks platform-side.
- No attempt to sandbox the compilation server for untrusted projects — the
  feature is binary on/off by trust.
