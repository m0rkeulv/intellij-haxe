# 05-debugger-flash-air-neko

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/hashlink/HashLinkBackend.java:52, src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/intellij/HxcppIntellijBackend.java:37
The branch added a scoped constructor to each backend and kept the old one as a
`this(..., List.of())` delegate, but every call site was migrated: the only
`new HashLinkBackend(...)` callers are `HashLinkDebugRunner:52`,
`HaxeTestDebugRunner:92` and `HashLinkBackendScopeTest:21` (all 4-arg), and the
only `new HxcppIntellijBackend(...)` callers are `HxcppIntellijDebugRunner:47`
and `HaxeTestDebugRunner:96` (both 2-arg). Both short constructors are now
zero-reference consumer-less API with no TODO naming what they wait for —
delete them (the "empty list disables the scoping" contract stays expressed by
the remaining constructor's javadoc).

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/HaxeFlashDebuggingUtil.java:166
`getAirTestDescriptor` is a near-verbatim copy of `getAirDescriptor` (lines
122-155): same SDK lookup + `flex.sdk.not.found` throw, same
`FakeFlexBuildConfiguration`, same `BCBasedRunnerParameters` starter, same
`startSession` / `OSProcessHandler` / `tieTogether` / `startNotify` /
`getRunContentDescriptor` tail. Only the `createConsole()` override and the
extra `sessionStopped` listener differ. That is the "same expression spelled in
2+ places gets one named home" rule: fold the two into one private
`airDescriptor(module, env, flexSdkName, adlCommandLine, sourceDirectories,
@Nullable FdbTestConsoleBridge bridge)` and let the two public entry points
supply the bridge (or not). The flex-SDK resolve + `FakeFlexBuildConfiguration`
pair, repeated a third time in `getDescriptor:72-77`, wants the same treatment
as a small `fakeBuildConfiguration(flexSdkName, launchTarget)` helper.

### should-fix — src/main/java/com/intellij/plugins/haxe/runner/debugger/flash/AirRunConfiguration.java:105
`effectiveFlexSdkName()` here and `FlashRunConfiguration.effectiveFlexSdkName()`
(FlashRunConfiguration.java:77) are byte-for-byte the same three statements —
own field if non-blank, else `HaxeToolPathResolver.resolveFlexSdkName(project,
null)`, else `""`. Duplicated domain knowledge (the Flex-SDK precedence chain)
belongs in its domain's class: add a non-null `flexSdkNameOrEmpty(project,
override)` (or an `@NotNull` overload of `resolveFlexSdkName`) to
`HaxeToolPathResolver` and have both configurations call it.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/flash/AirRunConfiguration.java:197
`Path copy = Files.createTempDirectory("haxe-air-run").resolve(...)` creates a
fresh temp directory on every parallel launch and nothing ever removes it — the
descriptor copies accumulate in the OS temp directory for the life of the
machine. Either register the copy with the platform's temp-file cleanup, delete
the directory when the adl handler terminates, or reuse a single per-project
directory keyed by the run configuration.

### minor — src/test/java/com/intellij/plugins/haxe/runner/debugger/hashlink/HashLinkBackendScopeTest.java:17
`@DisplayName("Debugger: hash link backend scope")` does not follow the
generated pattern `"<Kind>: <subject>"`: the kind for this module is
`HashLink debugger` and the subject is the class name minus `Test` and minus
the words the kind already carries — i.e.
`@DisplayName("HashLink debugger: backend scope")`. As written the kind is
generic and "hash link" has been de-camel-cased into the subject, so the name no
longer maps 1:1 back to the identifiers.

### minor — src/test/java/com/intellij/plugins/haxe/runner/debugger/hashlink/HashLinkBackendScopeTest.java:20
The `backend(List<String>)` factory sits above the `@Test` methods. Member order
in a class that has `@Test`s is constants, fields, setup/teardown, tests,
helpers, nested types last — move the helper below
`testWithoutDirectoriesEveryFileIsAccepted`.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/HaxeFlashDebuggingUtil.java:242
`printToSessionConsole(debugSession, "adl exited with code " + event.getExitCode() + "\n")`
puts user-visible session-console text inline, one line above a sibling message
that correctly goes through `HaxeDebuggerBundle.message("air.runner.instance.hint")`.
This is the run console the user reads, not developer log or `[dap]`
diagnostics, so it needs a key — e.g. `air.runner.adl.exit.code={0}`.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/HaxeFlashDebuggingUtil.java:259
`printToSessionConsole` evaluates `debugSession.getConsoleView()` twice — once
for the null test and again for the `print` — so the guard does not actually
protect the call if the view is swapped between the two reads. Name it once:
`ConsoleView console = debugSession.getConsoleView(); if (console != null) { console.print(...); }`.

### minor — src/main/java/com/intellij/plugins/haxe/runner/neko/NekoRunConfiguration.java:63
The no-module error reports `HaxeDebuggerBundle.message("hxcpp.runner.no.module")`
from a Neko configuration, even though the file already owns a `neko.runner.*`
key group and the sibling AIR configuration added its own `air.runner.no.module`
(with identical wording). Bundle keys are grouped by feature so a key sorts next
to its siblings; add `neko.runner.no.module` and use it.

### minor — src/main/java/com/intellij/plugins/haxe/runner/debugger/flash/AirRunConfigurationEditor.java:39
```java
String fromRuntimes = HaxeDebuggerBundle.message("flash.runner.editor.flex.sdk.from.haxe.sdk");
flexSdkSelector = new FlexSdkSelector(fromRuntimes);
```
is duplicated verbatim in `FlashRunConfigurationEditor.java:35-36`, and the AIR
editor borrows a `flash.runner.*` key while every other string it uses is
`air.runner.*`. Either give `FlexSdkSelector` a no-arg constructor that supplies
its own placeholder text (one home for the string), or add
`air.runner.editor.flex.sdk.from.haxe.sdk` so the AIR editor's keys stay in
their own group.
