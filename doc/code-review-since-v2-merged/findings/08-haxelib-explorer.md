# 08-haxelib-explorer

### should-fix — src/main/java/com/intellij/plugins/haxe/haxelib/HaxelibCacheManager.java:74
`forceReload()` is a pure forwarding wrapper: its whole body is `reload();`, and `reload()` is public and already called by `HaxePurgeCachesAction` and `HaxelibNotifier`. This is the "do not wrap a method in another method that adds nothing" rule — it binds no argument and adds no behaviour, it only renames. Delete `forceReload()` and have `HaxelibExplorerPanel.reload()` (line 219) call `manager.reload()`; move the useful sentence of its javadoc onto `reload()`, and drop the `{@link #forceReload()}` reference from the class javadoc (line 26).

### should-fix — src/main/java/com/intellij/plugins/haxe/haxelib/HaxelibCacheManager.java:129
`refreshLibraryInfo(String)` has zero callers anywhere in `src/` — the only occurrence besides its own declaration is the `{@link #refreshLibraryInfo}` in the class javadoc that advertises it as one of "the explicit invalidations". Consumer-less API without a TODO naming the wiring it waits for is dead code. Either delete it (and the javadoc claim), or wire it where it belongs — `HaxelibExplorerActions.mutate` already refreshes the installed picture after install/remove but leaves the stale `haxelib info` for the mutated library cached, which is exactly what this method is for.

### should-fix — src/main/java/com/intellij/plugins/haxe/haxelib/HaxelibCacheManager.java:174
`sdkContext()` uses `ReadAction.computeBlocking` (lines 174 and 184) and is reached only from `fetchInstalledLibraryData`/`fetchAvailableForDownload`/`fetchLibraryInfo`, i.e. from the pooled threads the class javadoc mandates ("Fetches run external processes — call off the EDT"). The platform javadoc for `computeBlocking` says *"Avoid usage in background threads as it will likely cause UI freezes. Use it only under modal progress or from EDT"* and names `NonBlockingReadAction#executeSynchronously` as the background-thread form. Same defect at `HaxelibExplorerPanel.java:732` (`haxeModule()`), which is called from the explorer's pooled tasks — and that very file already uses the right form twice (`ReadAction.nonBlocking(...).executeSynchronously()` at lines 644 and 683), so the branch is inconsistent with itself. Switch both to `ReadAction.nonBlocking(...).executeSynchronously()`.

### should-fix — src/main/java/com/intellij/plugins/haxe/haxelib/HaxelibCacheManager.java:47
`installedLibraries` and `availableLibraries` stay plain `HashMap`s while the same diff converted `instances` and the new `libraryInfos` to `ConcurrentHashMap` and added `volatile` to `installedIndex` — so the class is now half thread-safe. The explorer writes them from a pooled thread (`reload()` → `getInstalledIndex()` / `getAvailableLibraries()`) while the completion contributors (`HXMLHaxelibCompletionContributor`, `XmlHaxelibCompletionContributor`) read and `put` into `availableLibraries` from completion threads via `getAvailableLibraries()`/`fetchAvailableVersions()`. Concurrent `putAll` + `put` + iteration on a `HashMap` is a real corruption/CME risk. Make both `ConcurrentHashMap` like their siblings.

### should-fix — src/main/java/com/intellij/plugins/haxe/haxelib/HaxelibCacheManager.java:216
`dispose()` sets `module = null`, but the branch's new `sdkContext()` dereferences `module` three times (`ModuleRootManager.getInstance(module)`, `module.getName()`, `guessModuleDir(module)`) from pooled threads. The explorer's hydration sweep runs one `haxelib info` process per installed library and can be in flight for minutes; a project close during it disposes the module, nulls the field, and the next fetch NPEs inside the pooled task. Capture the module into a local at the top of `sdkContext()` and bail when it is null (or keep the field non-null and guard on a `disposed` flag), and note in the javadoc that a disposed cache answers nothing.

### should-fix — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerActions.java:180
`confirmRemoval(String)` is spelled out identically in `RemoveVersion` (lines 180-185) and `RemoveLibrary` (lines 228-233) — same dialog, same two bundle keys, same `== Messages.YES`. The same expression in 2+ places gets one named home; move it to the `ExplorerAction` base class next to `mutate`, where both subclasses already inherit `panel`.

### should-fix — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerPanel.java:632
At 809 lines this class runs the loading/generation bookkeeping, the tree model, the filter predicates, the cell renderer, the refresh action — and, in lines 632-678, a documentation pipeline that has nothing to do with the tree: reading files, driving `HaxeDocumentationRenderer`, wrapping in `<html><body>`, `adaptImagesForSwing` and `directoryUrl`. That last block is the details pane's job and splits cleanly along the data/presentation line: move `loadDocs`, `adaptImagesForSwing` and `directoryUrl` into a small class beside `HaxelibDetailsPane` (it already owns `DocTab`) and let the panel call one method. `HaxelibLocalDocs` finds the files; nothing then leaves the tree class to know about Swing's HTML viewer quirks.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibOverviewHtml.java:65
Several `StringBuilder` append chains run 3+ calls across wrapped lines instead of one call per line — lines 65-66 (seven `append`s over two lines), 73-74, 122, 127-129. The rule for wrapped string expressions points at the better fix here: these are HTML fragments, so a text block with `.formatted(...)` shows the markup's shape, e.g. the website row becomes one `"""<tr><td>%s</td><td><a href="%s">%s</a></td></tr>""".formatted(label, url, url)` appended in a single call, and the release row likewise.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerFilters.java:67
`private ToggleAction toggle(filter, text, icon) { return new FilterToggle(filter, text, icon); }` forwards all three arguments to the constructor and adds nothing — a pass-through wrapper. Call `new FilterToggle(...)` directly in `createToggleGroup()` and delete the method.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerFilters.java:22
The class javadoc says "plus the one narrowing restriction (updates only)", but the enum and `createToggleGroup()` carry two narrowing filters: `ONLY_UPDATES` and `BEHIND_LATEST`. A comment must state what the code does now; fix the wording to name both (updates-available and behind-latest-selection), or the next reader trusts a description the code contradicts.

### minor — src/main/resources/messages/HaxeBundle.properties:429
`haxelib.explorer.filter.group=Filter` has no caller — the whole explorer feature never asks for it (`HaxelibExplorerFilters` builds the strip from the six per-filter keys). A bundle key with no caller is dead; delete the line.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerPanel.java:306
Two calls carry a multi-line value inside their argument list instead of naming the payload first. `rows.sort(Comparator.comparing((LibraryRow row) -> !row.installed()).thenComparing(...))` spans lines 306-307 — extract `Comparator<LibraryRow> installedFirstByName = ...;` so the call reads `rows.sort(installedFirstByName);`. Same at lines 617-619, where a three-line lambda calling `details.showLibrary(...)` with seven arguments sits inside the `onUi(...)` call — extract `Runnable render = () -> details.showLibrary(...);` and leave `onUi(selectionGeneration, expected, render);`.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerPanel.java:23
Six classes are imported one by one from our own `com.intellij.plugins.haxe.haxelib` package (`HaxelibCacheManager`, `HaxelibInstalledIndex`, `HaxelibLibraryInfo`, `HaxelibLocalDocs`, `HaxelibSdkUtils`, `HaxelibUtil`). The rule is to wildcard-import our own packages at 4+; make it `com.intellij.plugins.haxe.haxelib.*` and leave the platform imports explicit.

### minor — src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerPanel.java:548
`static List<VersionEntry> versionEntries(...)` is package-private, but its only caller is `setVersionChildren` in the same file and there is no explorer test (only `HaxelibLocalDocsTest` and `HaxelibLibraryInfoTest` exist). Package-private visibility here signals a collaborator or a test that does not exist; make it `private`.
