# 01-toolwindow-core

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeBuildFileScanner.java:31 (also HaxeBuildFile.java:9, HaxeBuildFileType.java:11)
Package direction violation: `HaxeBuildFile`, `HaxeBuildFileType` and `HaxeBuildFileScanner` are model-side code (file discovery, type detection — no UI at all) living under the UI package `v2.toolwindow.tree`, and a dozen `v2/buildtools` classes import them (`HaxeLibrarySync`, `HaxeDefineContextService`, `HaxeCustomCommands`, `LimeProjects`, `NmeProjects`, `HaxeCompileCommands`, `HaxeLimeProjectInfoService`, `HaxeNmeProjectInfoService`, `HaxeBuildFilesProjectAware`). The checklist is explicit: "model/buildtools code never imports UI/toolwindow classes — a constant or store being the excuse means it lives in the wrong package." These three types (and the stores buildtools also imports, reviewed in their own chunk) belong beside `HaxeBuildFileInfo`/`HaxeBuildFileInspector` in `v2/buildsystem` (or in `v2/buildtools`); the tool window then imports them, not the other way round.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerStatusPanel.java:131
Feature code calls the display-protocol client directly: `new HaxeDisplayClient("127.0.0.1", port).serverMemory(List.of())`. CLAUDE.md's compiler-services rule says feature code never touches the `display-protocol` client/transport — a compiler-backed capability is built as a reusable service in `v2/display/` first (this is the only such direct use outside `HaxeCompilerDisplayService`). Move the `server/memory` fetch into `HaxeCompilerDisplayService` (which already owns connection + capability gating) and have the panel consume that; the next feature wanting memory stats then reuses it.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeToolWindowNodes.java:264-273
`CompilationServerNode`'s javadoc is stranded on the wrong declaration: the block "Compilation server row: shows the project server's state…" sits immediately above `record ServerFailureLink`, followed by a second javadoc, so the descriptive doc attaches to nothing and `CompilationServerNode` (declared at line 275) has no doc at all. This is the "a comment travels with the declaration beneath it" trap. Move the `CompilationServerNode` javadoc down to its record (or declare `ServerFailureLink` after it).

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowModelBuilder.java:385-411
`defaultBuildCommand` and `presentableBuildCommand` re-spell the per-type dispatch that `addDefaultActions` (line 286) already performs — three switches over `HaxeBuildFileType` that must each grow with every new type, and they have already drifted: for lime files `addDefaultActions` renders `"lime build <flags>"` while `presentableBuildCommand` renders `"lime build <file> <flags>"` (same for NMML), so the identical command displays differently on the action row and the compile-command row. The default build command is exactly the `ActionNode` named `BUILD_ACTION` that `buildFileActions` already produced for the same file — `compileCommandNode` should pick that node from `chosen.actions()` (it already does this for overrides) instead of recomputing command and presentable through two more switches.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerConsoleWindowFactory.java:216-219
The predicate "this server id is running" is spelled three times: `isTabServerRunning` (`info.id().equals(serverId) && info.running()`), inline in `updateStatuses` (line 125), and again as the filter in `HaxeServerStatusPanel.runningPort` (line 163). Same predicate in 2+ places gets one named home — the natural spot is `HaxeCompilationServerManager` (e.g. `isRunning(serverId)` / `runningPort(serverId)` beside the existing no-arg `isRunning()`), which both files already call.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeToolWindowTreeRenderer.java:209
`isBuildAction` mixes case handling: `name.equalsIgnoreCase("build") || name.equals("compile") || name.equalsIgnoreCase("clean")` — a custom action named "Compile" gets the run icon while "Build"/"Clean" match case-insensitively. Use `equalsIgnoreCase` for all three (or a `static final Set` + lower-cased lookup).

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeToolWindowTreeRenderer.java:213-217
`groupLabel` duplicates the kind→bundle-key mapping already implemented by `GroupNode.speedSearchText()` (HaxeToolWindowNodes.java:176) — the renderer can call `groupNode.speedSearchText()` or the mapping can live on `GroupKind`. Same file: the dropdown marker `" ▾"` is spelled five times (lines 61, 94, 120, 126, 132); one named constant (or an `appendChooserArrow()` helper) gives it a single home.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerConsoleWindowFactory.java:130-131
Multi-line value inside a call: `content.setIcon(status.hasFailures() ? AllIcons.General.Error : running ? AllIcons.General.InspectionsOK : null)` wraps a nested ternary across two lines inside the argument list. Extract `Icon icon = …` (a plain if/else chain reads better than the nested ternary) and call `content.setIcon(icon)`.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:213
Non-trivial lambdas that should be named methods: `refreshTree`'s `.onSuccess(scan -> AppExecutorUtil.getAppExecutorService().execute(() -> updateTree(scan)))` nests a lambda inside a lambda (rule: a nesting lambda becomes a private method), and `executeAction`'s stream filter (lines 554-556) is a three-term predicate with an instanceof pattern that wraps — extract e.g. `matchesAction(candidate, actionNode)`.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:461-465
`getSelectedGroupMissingLibraries` evaluates `node.getUserObject()` in both branches of the if/else-if chain; the rule says the repeated expression becomes one local named above the chain (`Object userObject = node.getUserObject();`), which also sets the chain up as a pattern switch with `when` guards.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowNavigation.java:54
`fileDescriptor(project, file, int offset)` is only ever called with `offset` 0 (lines 42, 43, 46, 61) — `DefineNavigatable` builds its own `OpenFileDescriptor` for the non-zero case. Drop the dead parameter.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerStatusPanel.java:116
The javadoc on `fetchServerStats` says "no-op while the server is down", but the down path (port <= 0) is not a no-op: it sets `serverStats` to the "unavailable" message and re-renders. Comments state behaviour — fix the sentence to say the stats line shows unavailable while the server is down.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:101-102
Stray double blank lines: after `TREE_POPUP_PLACE` (lines 101-102), inside `buildFileNode` (lines 338-339), and in HaxeBuildFileScanner.java between `addIfBuildFile` and the `DetectedType` record (lines 78-79). Blank lines mark group boundaries — one is enough at each spot.
