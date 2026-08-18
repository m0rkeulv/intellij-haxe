# 09-toolwindow-runconfig

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:1017
The branch adds `private RelativePoint selectionPoint()` whose body is character-for-character the existing public `getSelectionPopupPoint()` at line 732 (same `getSelectionPath()` / `getPathBounds` / `RelativePoint(tree, ...)` fallback). Duplication checklist: the same expression gets one named home. Delete `selectionPoint()` and have `TreeEnterAction.actionPerformed` (line 928) call `getSelectionPopupPoint()`.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/actions/HaxeRunUnitTestsAction.java:58 and HaxeDebugUnitTestsAction.java:61
`resolveTestsPath(Object)` — javadoc, switch and all — is copied verbatim into both new actions ("the row's own file, or the container's marked/suggested one"). It encodes one piece of domain knowledge (how a selection maps to a tests build file) in two places, so a new selectable row type has to be added twice. Move it to `HaxeToolWindowPanel` (it already owns `testsPathFor` and `getProjectRootContainerId`) and have both actions call the one method.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/actions/HaxeRemoveNodeAction.java:38
The toolbar path removes a build file with no confirmation (`HaxeBuildFilesStore...removeFile(...); panel.refreshTree();`), while the branch's own Delete-key path (`HaxeToolWindowPanel.confirmAndRemoveBuildFile`) asks first and re-uses the remove/hide wording. The same action reached two ways behaves differently, and the store call is duplicated. Make `confirmAndRemoveBuildFile` public — as `confirmAndRemoveModule` already is — and call it from the `case BuildFileRow row ->` arm.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowModelBuilder.java:409
`boolean connectEligible = HaxeCompileCommands.isConnectEligible(project, environmentSdk, command) && !HaxeCompileCommands.producesSwf(project, buildFile.file());` restates, in the UI model builder, exactly the composite predicate `HaxeCompileCommands.buildResolved` computes at HaxeCompileCommands.java:111. The swf-corruption rule is `HaxeCompileCommands`' domain knowledge; expose it there as one named method (e.g. `connectEligible(project, sdk, command, file)`) and call it from both, so the tool window can never drift from what the build path actually does.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerDisplayService.java:157
`hxmlContextArgs` re-implements the selected-section expansion that `HxmlProjects.scopeToSelectedSection` (HxmlProjects.java:55) already performs: `sectionContents` → `size() < 2` bail → `HaxeBuildSections.selectedIndex` → `HxmlArguments.parseLines(section.lines().toList())`. Only the surrounding assembly differs (prefix `--cwd` vs. splice over the file token). Extract the shared middle as e.g. `HaxeBuildSections.selectedSectionArguments(project, file)` and let both call it — otherwise the display context and the compile can silently disagree about which section is selected.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowModelBuilder.java:123
`resolveTestsPaths` filters the candidates it hands `HaxeTestsBuildFileStore.resolveTestsFiles` down to builds whose libraries declare a known test framework, but the other consumer of the same store method, `HaxeLibrarySync.testsBuildFiles` (HaxeLibrarySync.java:190), passes every build file unfiltered. The two answers for one container can differ, so a build can feed the module's resolve scope as a "tests build" while the tree refuses to show it a Tests row — and the javadoc's claim "in both cases only builds DECLARING a known test framework lib" is only true of this caller. Push the framework filter into `resolveTestsFiles` so there is one answer.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:863
`handleSingleClick` and `handleDoubleClick` now do nothing but forward to `interactWithNode` / `activateNode` — no argument binding, no added behaviour. "Do not wrap a method in another method that adds nothing." Inline both at the `mouseClicked` call sites (lines 844 and 846) and delete them.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:138
`new TreeEnterAction().registerCustomShortcutSet(CustomShortcutSet.fromString("ENTER"), tree, this);` hand-builds a shortcut set the platform already ships as `CommonShortcuts.ENTER` (non-deprecated in 2026.2), and the very next line already uses `CommonShortcuts.getDelete()`. Use `CommonShortcuts.ENTER`; the `CustomShortcutSet` import then drops out.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeToolWindowNodes.java:109
`record TestsGroupNode(String ownerId, int count)` — `ownerId()` is never read anywhere (unlike `ActionsGroupNode.ownerId`, which several actions consume), and `count` is only ever the literal `1` from `buildTestsGroupNode` (HaxeToolWindowPanel.java:339), so the renderer's "(1)" suffix is a constant. Drop `ownerId`, and either drop `count` and the suffix or feed it the real child count.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/actions/HaxeDebugUnitTestsAction.java:55 (same at HaxeRunUnitTestsAction.java:52)
`update` sets the explanatory description only on the disabled branch and never clears it when the action becomes enabled. Action instances are reused across popup invocations, so once a container without a tests file (or an undebuggable target) has been selected, the "no tests file" / "unsupported target" hint stays on the presentation for every later selection. Set the description in both branches (`null` when enabled).

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowPanel.java:259
`rememberTestsPaths` clears `testsPathsByContainer` at the top but only ever assigns `projectRootContainerId`, never resets it. A scan that returns no project-root container leaves the previous id in place, and `getProjectRootContainerId()` then answers with a container that no longer exists. Set it to `null` alongside the map clear.

### minor — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeProgramLaunches.java:75
`case FLASH -> !output.endsWith(".swf") ? null : isAirOutput(output) ? AIR_APP : FLASH_APP;` packs a negated test and a nested ternary into one switch arm — three decisions with no parentheses to guide the eye, in the method documented as "the single authority on which run configuration launches which target output". Give the arm a block with a named local (`boolean swf = output.endsWith(".swf");`) or an `if`, the way the surrounding arms read.

### minor — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/actions/HaxeRemoveNodeAction.java:29
The class was renamed from `HaxeRemoveModuleAction` to match its widened job, but its constructor still registers `haxe.toolwindow.remove.module` as text and `haxe.toolwindow.remove.module.description` as the description, and `update` only ever swaps the *text*. With a build-file row selected the button reads "Remove Build File" while its tooltip still describes removing a module. Add a matching description key per branch (or a neutral one covering both).

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerDisplayService.java:118
The extracted `LimeProjects.displayArguments` returns bare `null` for timeout, non-zero exit and `ExecutionException` alike, so the log line lost the detail the old body carried (`": exit " + output.getExitCode()`, `e.getMessage()`) and now reads only "lime display failed for <file>". A lime display failure is exactly the case where the exit code is the whole diagnosis; have the helper log the reason itself, or return it.

### minor — src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionBeforeRunTaskProvider.java:381
`debugAdditions` and `singleRunDebugAdditions` (line 392) share their entire head — `findFileByPath`, null bail, `HaxeBuildFileScanner.detectType`, null bail, `ReadAction.computeBlocking` — and differ only in what they compute from `(project, file, type)`. Factor the resolution into one private `HaxeBuildFile` lookup used by both.

### minor — src/test/java/com/intellij/plugins/haxe/v2/runconfig/HaxeDebugSupportTest.java:10
Two new test classes in the same package use two different display-name Kinds for the same feature area: `"Run config: debug support"` here vs `"Run configurations: program launches"` in `HaxeProgramLaunchesTest`. The Kind is meant to be one generated word per area — pick one spelling. Also `testSessionsAddInterpFlashAndNodeJs` ends with `assertFalse(HaxeDebugSupport.supportsProgramDebug(HaxeTarget.INTERP), "programs have no interp lane")`, a *program* assertion in a test whose display name promises test sessions; move it to the program test so the name maps 1:1 to what runs.

### minor — src/test/java/com/intellij/plugins/haxe/v2/runconfig/HaxeProgramLaunchesTest.java:26
The `info(HaxeTarget, String)` factory sits above the `@Test` methods. Member order for a class with `@Test`s is constants, fields, setup/teardown, tests, helpers — move it below the two tests.
