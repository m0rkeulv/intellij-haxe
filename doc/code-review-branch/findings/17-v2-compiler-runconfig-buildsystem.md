# 17-v2-compiler-runconfig-buildsystem

### fix-now — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionRunConfiguration.java:89
`checkConfiguration()` calls `HaxeCompileCommands.resolveAction(...)` bare, but that
method's javadoc says "Call in a read action" — it goes through
`HaxeContainers.containerIdFor` (file-index lookup) and `HaxeBuildFileScanner.detectType`.
Every other call site honors the contract: `getState().startProcess()` in this same file
(line 107) and `HaxeActionBeforeRunTaskProvider.executeTask` (line 183) both wrap the
identical call in `ReadAction.computeBlocking`, and the editor's own comment
(HaxeActionRunConfigurationEditor.java:99) states "module lookup hits the file index -
EDT has no implicit read access". Validation in the Edit Configurations dialog can
therefore hit a read-access assertion. Wrap the call in `ReadAction.compute`.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:109-111
`targetForFlag` carries BOTH `@NotNull` and `@Nullable`, with the javadoc sandwiched
between them:
```java
@NotNull
/** The target a compiler flag selects ... or null for non-target flags. */
@Nullable
public static HaxeTarget targetForFlag(@NotNull String flag) {
```
The method returns null by design (`TARGET_FLAGS.get`), so `@NotNull` is a false
contract that misleads null analysis at the caller (HaxeGeneratedDumpService null-checks
the result). Delete the `@NotNull` and move the javadoc above the annotation so doc
tools attach it.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileInspector.java:7, HaxeBuildFileNavigation.java:3
Package direction: the model-side `v2/buildsystem` package imports UI/toolwindow
classes — `HaxeBuildFileInspector` imports `v2.toolwindow.tree.HaxeBuildFile` and
`HaxeBuildFileNavigation` imports `v2.toolwindow.tree.HaxeBuildFileType`. The checklist
says model/buildtools code never imports UI/toolwindow classes, and "a constant or
store being the excuse means it lives in the wrong package": `HaxeBuildFile` (a
VirtualFile + type record) and `HaxeBuildFileType` are build-model data used across
buildsystem, buildtools and runconfig — they belong in `v2/buildsystem` (the type's
Swing `Icon` presentation staying UI-side), with the toolwindow importing them, not
the reverse.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:44-58
`parse` simulates out-parameters with one-element arrays (`HaxeTarget[] target`,
`String[] targetOutput`) and threads six accumulators through the recursive
8-parameter `parseInto`. A small private mutable holder (defines, libraries,
classpaths, target, targetOutput, visitedIncludes in one object) collapses the
signature to `parseInto(content, includeResolver, acc)` and removes the array
indirection; `parse` then builds the record from the holder.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionBeforeRunDialog.java:115-130, HaxeActionRunConfigurationEditor.java:116-134
`refillActionCombo` is duplicated nearly line for line in both classes: capture the
previous selection (falling back to the editor item), rebuild a
`DefaultComboBoxModel` from `ReadAction.computeBlocking(() ->
HaxeCompileCommands.availableActionNames(project, file))`, re-add an unknown previous
entry, restore the selection. Same predicate, same read-action comment, same
null/blank handling. Give the dance one named home (a small package-private helper in
`v2/runconfig`, e.g. `refillCombo(combo, values, previous)`), and both classes call it.

### minor — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionBeforeRunTaskProvider.java:259-268
`debugAdditions`' javadoc says "Call in a read action", but its callees are all safe
without one (`detectType` wraps its own PSI access in `ReadAction.compute`; the
lime/nme target-flag lookups are plain store reads) and it wraps the one inspect call
itself in `ReadAction.computeBlocking` (line 266). The two call sites then disagree:
`executeTask` (line 192) calls it bare while the dialog (HaxeActionBeforeRunDialog.java:103)
wraps the whole call in another `ReadAction.computeBlocking`. Drop the stale javadoc
claim and the dialog's outer wrap so the contract and callers agree.

### minor — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeDebugAdditions.java:14-29
The class named for the concept owns only a third of it: `HaxeDebugAdditions` handles
the HXML per-target case, while the lime-family and NMML debug additions (the `-debug`
flag and the debug-server library injection, lines 270-296 of
HaxeActionBeforeRunTaskProvider) live inside the before-run provider, including the
`DESKTOP_CPP_TARGETS` domain constant. Move the per-build-file-type dispatch into
`HaxeDebugAdditions` (or the lime/nme knowledge into LimeProjects/NmeProjects) so the
next consumer of "debug additions" finds one home instead of a split.

### minor — src/main/java/com/intellij/plugins/haxe/v2/compiler/HaxeLanguageLevel.java:21
`VERSION_PATTERN = Pattern.compile("^\\s*(\\d+)\\.(\\d+)")` has no comment saying what
it matches; the checklist requires one above every regex. One line naming the groups
suffices, e.g. "leading major.minor of a version string (4.3.7, 5.0.0-rc.1) — suffixes
ignored".

### minor — src/main/java/com/intellij/plugins/haxe/v2/compiler/HaxeLanguageLevel.java:31-33
`getMajor()` has zero callers anywhere in the repo (`fromVersionString` reads the
private field directly). Consumer-less API without a TODO naming the wiring it waits
for is dead code — delete it.

### minor — src/main/java/com/intellij/plugins/haxe/v2/compiler/settings/HaxeCompilerSettings.java:62-65
The `getEffectiveLanguageLevel(Module)` default overload has no callers — every
consumer (HaxeLanguageLevelUtil, tool window, tests) uses the `String` overload.
Consumer-less convenience API without a TODO; delete it (a future caller can trivially
write `module.getName()`).

### minor — src/main/java/com/intellij/plugins/haxe/v2/compiler/settings/ui/HaxeCompilerConfigurable.java:72-73
The publish-then-restart pair
(`syncPublisher(HaxeBuildConfigListener.TOPIC).buildConfigurationChanged()` +
`DaemonCodeAnalyzer...restart("haxe: language level changed")`) is duplicated verbatim
in `HaxeLanguageLevelUtil.setLanguageLevel` (lines 69-70), down to the same restart
reason string. Give it one named home — e.g.
`HaxeLanguageLevelUtil.notifyLanguageLevelChanged(project)` — and call it from both.
