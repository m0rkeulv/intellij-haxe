# 04-buildtools-core

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildtools/ (package direction, whole chunk)
Nearly every file in this chunk imports `v2.toolwindow` classes: `HaxeBuildFilesProjectAware` (HaxeBuildFilesStore, HaxeBuildFile, HaxeBuildFileScanner), `HaxeCompileCommands` (HaxeEnvironmentStore, HaxeCustomActionsStore, HaxeBuildFileScanner, HaxeBuildFileType), `HaxeCustomCommands`, `HaxeProjectTaskRunner`, `HaxeToolPathResolver`, `LimeProjects`/`NmeProjects` (HaxeTargetOptions, HaxeTargetSelectionStore), `HaxeModuleWorkspace.kt`, `HaxeSourceRootsInitializer.kt`. The checklist says buildtools/model code never imports UI/toolwindow classes, and "a constant or store being the excuse means it lives in the wrong package" — which is exactly the case here: the imported classes are stores, a scanner, a file-type enum and target-option facts, all model-shaped and UI-free. Fix: move `HaxeEnvironmentStore`, `HaxeCustomActionsStore`, `HaxeTargetSelectionStore`, `HaxeTargetOptions`, `HaxeBuildFilesStore` and the `tree.HaxeBuildFile*` scanner/type classes out of `v2/toolwindow` into `v2/buildtools` (or `v2/buildsystem`), leaving `toolwindow` with only the actual UI.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxelibPathParser.java:61,78
`parseClasspaths` and `parseVersion` have no production caller — only `HaxelibPathParserTest` exercises them (`HaxeLibrarySync` uses only `parseSections`). Consumer-less API is dead code per the checklist: delete both, or leave a TODO naming the wiring they wait for.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeCompileCommands.java:86-107,196-218
Dispatch on `HaxeBuildFileType` is spelled three times in this file — the `switch` in `defaultCommand`, the else-if chain in `availableActionNames` and the if-chain in `actionCommand` — plus a fourth in `HaxeCustomCommands.selectedTargetFlagOf`. Each new build-file type must touch all four sites. The checklist wants a per-type switch that must grow with every type to become a method the types carry themselves: give `HaxeBuildFileType` (or a per-type strategy next to it) `defaultActionNames()` / `actionCommand(...)` and collapse the callers. At minimum the two if/else-if chains type-testing the single `type` value should become pattern/enum switches like `defaultCommand` already is.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxelibPathParser.java:51,66 and 40-47,81-87
The error-line predicate `trimmed.startsWith("Error") || trimmed.contains("is not installed")` is spelled twice (parseSections:51, parseClasspaths:66), and the `-D name=version` marker parsing (`startsWith("-D ")`, `indexOf('=')`, `substring(3, equals)`, `substring(equals + 1)`) is duplicated between parseSections:40-47 and parseVersion:81-87. Same predicate/expression in 2+ places gets one named home: a private `isErrorLine(String)` and a private record-returning `parseDefineMarker(String)` used by both call sites.

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/v2/buildtools/HaxeSourceRootsInitializer.kt:103,106
`applyToContentRoot` takes `builder: com.intellij.platform.workspace.storage.MutableEntityStorage` and `urlManager: com.intellij.platform.workspace.storage.url.VirtualFileUrlManager` fully qualified. "No fully-qualified names in code — import instead"; there is no name collision (sibling files import both types plainly). Add the two imports and shorten the signature.

### minor — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeProjectTaskRunner.java:41
`@CustomLog` generates a `log` field that nothing in the class uses — the failure paths print to the build console instead. Drop the annotation (and the lombok import) or actually log the `ExecutionException`.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/buildtools/HaxeSourceRootsInitializer.kt:108
`contentRoot.url.url.removePrefix("file://")` hand-rolls URL-to-path conversion; the platform already provides it and `VfsUtilCore` is imported in this file — use `VfsUtilCore.urlToPath(contentRoot.url.url)` (it also normalizes the protocol separator instead of assuming exactly `file://`).

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/buildtools/HaxeSourceRootsInitializer.kt:54
`WorkspaceModel.getInstance(project).currentSnapshot` is fetched inside the per-module loop; the snapshot does not change across iterations of this read action. Hoist it above the `for` — one named local, per the repeated-expression rule.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/buildtools/HaxeModuleWorkspace.kt:50-53
`builder.addEntity(ModuleEntity(name, listOf(ModuleSourceDependency), source) { ... })` buries a multi-line entity construction inside the call. Per the no-multi-line-values-inside-calls rule, name the payload: `val moduleEntity = ModuleEntity(...) { ... }` then `builder.addEntity(moduleEntity)`.

### question — src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeContextHealth.java:62
`record` fires `HaxeBuildConfigListener.TOPIC` directly on the caller's thread, and its main caller (`HaxeCompilerDisplayService`) records outcomes from background threads — while `HaxeModuleSdkApplier` and `HaxeModuleWorkspace` deliberately hop to the EDT before publishing the same topic (and the sibling `HaxeCompilationServerListener` documents "Delivered on the EDT"). Current subscribers happen to be thread-safe (`refreshTree` uses `ReadAction.nonBlocking`), but the topic's delivery thread is now caller-dependent. Either document the topic as any-thread or publish from `record` via `invokeLater` so all publishers agree.
