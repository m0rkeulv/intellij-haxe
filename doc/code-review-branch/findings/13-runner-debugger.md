# 13-runner-debugger

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/HaxeRunConfigurationType.java:53
The new javadoc claims "the platform always writes {@code factoryName}, so the array order below is purely the display order" — verified against the platform, that is not true for loading: `RunManagerImpl.getFactory` (2026.2 sources, line 1202) resolves a null `factoryId` to `configurationFactories.firstOrNull()`. The comment this branch deleted guarded exactly that case ("first: pre-group configurations saved without a factory name resolve to it"). With `HaxeActionConfigurationFactory` now first, a configuration saved by a pre-group plugin version without a `factoryName` attribute deserializes as a v2 action configuration instead of the legacy Haxe Application flavour. Either put `LegacyHxcppConfigurationFactory` (which keeps the historical "Haxe Application" id) first, or correct the javadoc if the pre-group case is deliberately dropped — the current comment documents an invariant the platform does not provide.

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:437
`container.addStackFrames(stackFrames.subList(firstFrameIndex, stackFrames.size() - 1), true)` — `subList`'s upper bound is exclusive, so the deepest stack frame is always dropped, and a single-frame stack shows no frames at all (`subList(0, 0)`). The off-by-one is carried over verbatim from the deleted `HaxeDebugRunner` (develop line 789), but this is a new file and the bug is unambiguous: the bound should be `stackFrames.size()`.

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:278
Stopping the session before the debuggee connects (the normal way to abandon the new remote-listen mode) closes `serverSocket`, which makes the blocking `accept()` throw `SocketException`; `start()`'s catch-all then surfaces "Debugging loop failed: java.net.SocketException…" as an ERROR notification. The same happens when Stop closes `debugSocket` mid-`readMessage`. Legacy carried the same flaw, but remote mode makes stop-before-connect a routine action, not an edge case. In the catch in `start()` (line 120), return quietly when the sockets were closed by `stop()` (e.g. check a stopped flag or both socket fields being null) instead of notifying.

### should-fix — src/main/resources/messages/HaxeBundle.properties:26
`runner.configuration.name=Haxe Application` is now a dead bundle key: its only caller was the deleted `HaxeRunConfigurationType.HaxeFactory.createTemplateConfiguration`, and a repo-wide grep finds no remaining reference (`runner.configuration.name.legacy` was correctly removed). Delete the key.

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:344
User-visible text hard-coded instead of bundled: `warn("Cannot set breakpoint")` (line 344), `error("Debugging loop failed: " + t)` (line 121) and the various `"Debugger protocol error: …"` strings (lines 230, 238, 259, 266, 321) are all shown to the user as balloon notifications via `NotificationGroupManager`, so they fall under the bundle rule, not the developer-log exemption — the sibling messages in this same class (`legacy.hxcpp.runner.listening`, the notification title) already live in `HaxeDebuggerBundle`. At minimum "Cannot set breakpoint" and the loop-failure message should become `HaxeDebuggerBundle` keys.

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/flash/FlashRunConfiguration.java:131
`resolveSwfOrNull` and `LegacyHxcppRunConfiguration.resolveAgainstProject` (lines 163–174) both re-spell the identical "Path.of → absolute? keep : resolve against project basePath, InvalidPathException → null" helper that already exists as `DapExecutableRunConfigurationBase.resolveExecutableOrNull` (lines 100–114) and again in `BrowserRunConfiguration` (line 162). Four copies of one expression in one class hierarchy — the checklist's "same expression in 2+ places gets one named home". Both new classes extend `DapRunConfigurationBase`; a `protected @Nullable Path resolveAgainstProject(String value)` there replaces all the copies.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/dap/ide/DapRunConfigurationBase.java:17
The javadoc says "Base for every DAP-debugger run configuration" and `DapCommandLineRunningState`'s says "the Run half of every DAP-debugger configuration", but this branch makes non-DAP configurations extend/use them: `FlashRunConfiguration` (fdb/Flex debugger) and `LegacyHxcppRunConfiguration` (hxcpp.DebugSocket protocol). The names and docs no longer describe what the classes are the base of — they are the generic module-carrying Haxe run-configuration base and the generic lazy command-line state. Rename (e.g. `HaxeRunConfigurationBase` / `HaxeCommandLineRunningState`) or at least fix both javadocs so the "Dap" claim stops misleading.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:721
`stripErrorAdornments` calls `Pattern.compile("ErrorEvaluatingExpression\\((.*)\\)", Pattern.DOTALL)` on every evaluation failure; the pattern is constant. Hoist it to a `private static final Pattern` (keeping the existing comment about what it matches), alongside the `callStackMarker` constant.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:387
`packageName.replaceAll("\\.", "/")` uses a regex for a plain character swap the JDK covers: `packageName.replace('.', '/')`. That also removes the need for the regex and its comment.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:355
The `DeleteBreakpointRange` listener body is only `// Could verify that the response was Deleted ...` — deferred work without a `TODO:` marker, which the style rules disallow ("designed for later" without a marker). Either make it `// TODO: verify the response is Deleted; a failed delete currently passes silently` or drop the comment and pass an explicitly empty listener.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/dap/ide/FqnNavigateLink.java:29
In the method this branch rewrites, `new Task.Backgroundable(project, "Resolving", true)` still carries a hard-coded, user-visible progress title (and a stray double space before `true`). While touching this code, the title should come from `HaxeDebuggerBundle`. Also line 21–23 leaves a double blank line behind the removed import.

### question — src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:97
`breakpointIds` is a plain `HashMap` written from the read-loop thread (the `AddFileLineBreakpoint` response listener at line 340 runs inside `readLoop`) and read/removed from the breakpoint-handler thread (`unregisterBreakpoint`, line 350) with no synchronization; `Value`'s `pendingChildrenNode`/`waitingForChildrenResults` have the same cross-thread pattern. Carried over from the legacy runner — is the single-threaded-enough assumption documented anywhere, or should these hop onto the existing `synchronized (this)` discipline the socket fields use?
