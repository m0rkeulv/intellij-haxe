# 14-cross-cutting

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:110
`reporterClasspath` is a per-type switch on framework library-name string literals
(`case "utest" -> HaxeTestReporterFiles.utestClasspath()...`) that must grow with every
new framework, and it re-spells the four ids that already have a single home in
`BuddyFramework.libraryName()` / `MunitFramework` / `TinkFramework` / `UtestFramework`.
CLAUDE.md: per-type switches that must grow become an interface method. Add a
`default Optional<String> reporterClasspath()` (or `List<String> reporterSources()`) on
`HaxeTestFramework` — the interface already carries `supportsFlash`, `reportingArgs`,
`singleRunTemplate` in exactly that shape — and let each framework answer for itself.
The four near-identical accessors in `HaxeTestReporterFiles.java:33-58` then collapse
into the one private `extract(...)` they already share.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestReporterFiles.java:65
The "SHA-256 short hex digest → cache directory under the IDE system path" idiom is
hand-rolled three times in three NEW files, each with a slightly different spelling:
here `HexFormat.of().formatHex(digest.digest()).substring(0, 16)` +
`Path.of(PathManager.getSystemPath(), "haxe", directoryName, contentHash)`;
`AirTestHost.java:107-115` uses `formatHex(hash, 0, 8)` +
`Path.of(PathManager.getSystemPath(), "haxe", "air-tests", artifactHash)`;
`HaxeTestSingleRuns.java:299-302` uses the 16-char form again. (`BrowserDebugBackend.java:274`
spells the same `PathManager.getSystemPath(), "haxe", …` prefix.) One named home —
e.g. `HaxeSystemPaths.cacheDirectory(String name, String hash)` plus a
`shortHash(byte[]...)` helper — removes three copies of the `NoSuchAlgorithmException`
rethrow as well.

### should-fix — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerPanel.java:94
The haxelib pseudo-version sentinels `"dev"` and `"git"` are spelled as bare literals
eleven times across two NEW files — `HaxelibExplorerPanel.java:94,98,103,431,432,455,551`
and `HaxelibLocalDocs.java:31,34,35,101` — while `HaxelibSemVer.java:52-58` already owns
them (`GIT_SCM = "git"`, `DEV = "dev"`, `GIT_VERSION`, `DEVELOPMENT_VERSION`). Promote
`HaxelibSemVer.DEV`/`GIT_SCM` to public constants (or add an
`isPseudoVersion(String)` predicate — `!"dev".equals(v) && !"git".equals(v)` is itself
written twice, at lines 103 and 455) and use them from both new files.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestDebugRunner.java:202
`buildFileModule(configuration)` and `sourceDirectories(configuration)` are copied
verbatim into `HaxeTestFlashDebugRunner.java:103-118`, and the copies have already
drifted: the DAP runner wraps the whole lookup in `ReadAction.computeBlocking` at the
call site (line 197) while the flash runner wraps only the
`ModuleUtilCore.findModuleForFile` half (line 107), and only the flash copy annotates
its parameter `@NotNull`. Both runners take the same `HaxeTestRunConfiguration`; hoist
the pair into one home (`HaxeTestRunConfigurations`, which both already import, or
`HaxeBuildClasspaths` next to `sourceDirectories`) so the read-action contract is
decided once.

### minor — src/main/resources/messages/HaxeBundle.properties:429
`haxelib.explorer.filter.group=Filter` is added by this branch and has no caller.
`HaxelibExplorerFilters.createToggleGroup()` builds a bare `DefaultActionGroup` and
never sets a popup title, and the six sibling `haxelib.explorer.filter.*` keys are all
referenced from that file. Either set the group's text from the key or drop the key.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:128
The "this target is IDE-hosted" predicate
`LimeProjects.FLASH_FAMILY_TARGETS.contains(targetFlag) || LimeProjects.BROWSER_TARGETS.contains(targetFlag)`
is written out twice (lines 128-129 and 531-532), and its two halves are tested
separately again at 466/475 and 590/598. CLAUDE.md: a repeated expression becomes one
named thing. `LimeProjects.isHostedTarget(String targetFlag)` — beside the two sets it
already owns — names the fact once and keeps the two sites from diverging.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestFlashDebugRunner.java:75
The three-line flash preamble — `FlexPluginGate.requireFlexPlugin()`,
`HaxeToolPathResolver.resolveFlexSdkName(project, null)`, then
`throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.no.flex.sdk"))` on
a blank result — is duplicated from `AirDebugRunner.java:49-53` (a third variant lives
in `AirRunConfiguration.java:234`). A `FlexPluginGate.requireFlexSdkName(Project)`
returning the resolved name would put the gate and its one error message together.
Separately, the test runner reporting under an `air.runner.*` key is a naming smell —
the message is now shared by two unrelated run flows.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestReporterFiles.java:20
The class javadoc cites "the READMEs under {@code resources/testing/}" (plural), but the
branch commits exactly one: `src/main/resources/testing/utestLiveReporter/README.md`.
The munit, buddy and tink reporter directories have no README. Either fix the citation
to name the utest README, or add the missing three.
