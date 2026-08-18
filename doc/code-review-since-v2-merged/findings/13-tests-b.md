# 13-tests-b

### should-fix — src/test/java/com/intellij/plugins/haxe/v2/testing/{Buddy,Munit,Tink,Utest}DetectionTest.java:81/81/83/111
`classByQName(String)` is copied character-for-character into all four detection tests
(BuddyDetectionTest.java:81, MunitDetectionTest.java:81, TinkDetectionTest.java:83,
UtestDetectionTest.java:111) and `methodOf(HaxeClass, String)` into three of them
(MunitDetectionTest.java:88, TinkDetectionTest.java:90, UtestDetectionTest.java:118) —
same body, same fail messages. CLAUDE.md's Test code section is explicit: "A helper
duplicated across sibling test classes belongs in the base." Fix: introduce one base for
these four (e.g. `HaxeTestFrameworkDetectionTestBase extends HaxeCodeInsightFixtureTestCase`)
holding `classByQName` and `methodOf`, and let each subclass keep only its `framework`
field, its `getBasePath()` and its own `configureFixtureProject()`.

### should-fix — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestRunnerPipelineTest.java:283, 341
Two helpers sit in the middle of the `@Test` run: `compileSingleRun` at line 283 (between
`testGutterSingleTestNarrowsMunitThroughThePatchedCollection` and the next `@Test` at 294)
and `compileTestsBuild` at line 341 (between the tink test and the utest single-run test).
Member order for a class with `@Test`s is constants, fields, setup/teardown, tests, helpers,
nested types last — both belong down with `newConfiguration` / `runThroughConverter`, above
the nested `RecordingEventsProcessor`.

### should-fix — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestRunnerPipelineTest.java:284-292, 342-355
`compileSingleRun` and `compileTestsBuild` end in the identical four-statement tail — build a
`GeneralCommandLine` with the resolved work directory, `runProcess(90_000)`, assert not
timed out, assert exit code 0 with the stdout+stderr dump. Only the resolution of
`HaxeCompileCommands.Resolved` and the two message strings differ. Extract one
`runCompile(HaxeCompileCommands.Resolved resolved, String what)` helper and let both callers
supply just the resolved command; the same-expression-in-two-places rule applies to test
helpers as much as to production code.

### should-fix — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestEventsConverterTest.java:20-21, 29-30, 38-39, 53-54
Four expected-value strings are `+`-concatenated fragments spanning two source lines, e.g.
`"##teamcity[testStarted name='cases.SampleTest.testPasses'" + " locationHint='haxe:test://…']"`.
CLAUDE.md: "A string expression that wraps onto more than one source line is a text block…
Wrapped fragments hide the shape of the text the code produces, which matters most in tests,
where that text is the fixture being read." These are exactly that — the expected TeamCity
service message is the fixture. Fix: use `"""` with a trailing `\` on the first line so the
logical single line is preserved.

### minor — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLocatorTest.java:94-99
`alpha.getContainingFile().getVirtualFile().getPath()` is spelled twice (condition and
message) and `beta.getContainingFile().getVirtualFile().getPath()` twice again — a 3-call
chain evaluated four times inside wrapping asserts. Both rules bite: the repeated expression
becomes one local, and "an assertion's condition states a fact, it does not compute one".
Extract `String alphaPath = …;` / `String betaPath = …;` above each assert. While there,
`beta` is dereferenced without the `assertInstanceOf(HaxeMethod.class, beta)` its `alpha`
counterpart gets (line 93), so a null/other-type result fails as an NPE instead of a named
assertion.

### minor — src/test/java/com/intellij/plugins/haxe/v2/testing/TinkDetectionTest.java:75
`assertTrue(resolved instanceof HaxeMethod method && "addsNumbers".equals(method.getName()), …)`
computes a two-term condition inside the assert, which then wraps to a second line — the case
the Test code rule names explicitly. Fix: `boolean landedOnTestMethod = resolved instanceof
HaxeMethod method && "addsNumbers".equals(method.getName());` above the assert (or
`assertInstanceOf(HaxeMethod.class, resolved)` followed by an `assertEquals` on the name,
which also yields a better failure message).

### minor — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlannerTest.java:232, and HaxeTestRunLineMarkerContributorTest.java:64-66
Same rule, two sites. `assertTrue(executableName.equals("hl") || executableName.equals("hl.exe"), …)`
(HaxeTestLaunchPlannerTest.java:232) is a two-term boolean in a wrapping assert — name it
(`boolean hlLauncher = …`). In HaxeTestRunLineMarkerContributorTest.java:64-66 the lambda
`gutter -> gutter.getTooltipText() != null && gutter.getTooltipText().startsWith("Run '")`
both re-reads `getTooltipText()` and buries a two-term test inside a stream pipeline that is
itself the assert's argument; a named `private static boolean isRunMarker(GutterMark)` used
as a method reference makes the assert read as one fact (the file already does this correctly
for `classMarker`/`methodMarker` at lines 45-48).

### minor — src/test/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlannerTest.java:333-334, 600-605
The same save/restore of `HaxeBuildToolSettings.isLiveTestReporting()` is spelled two ways in
one file: `testFlashBuildForcesTheLiveReporterIntoTheCompile` captures `boolean before` and
restores it, while `testDisablingLiveTestReportingDropsTheInjection` hard-codes
`settings.setLiveTestReporting(true)` in its `finally`. It happens to match today's default
(`HaxeBuildToolProjectSettings.State.liveTestReporting = true`), so it is not a live bug, but
it duplicates a default the settings class owns and silently starts leaking state if that
default ever flips. Use the capture-and-restore form in both, or pull it into one
`withLiveTestReporting(boolean, Runnable)` helper.

### minor — src/test/java/com/intellij/plugins/haxe/v2/runconfig/HaxeDebugSupportTest.java:10 and 15/24/31/44, HaxeTestRunLineMarkerContributorTest.java:19
`@DisplayName` drift from the generated pattern. (a) The two `v2/runconfig` classes disagree
on the Kind: `"Run config: debug support"` vs `"Run configurations: program launches"` — one
Kind per area, pick one. (b) `HaxeDebugSupportTest`'s methods are the only ones in the chunk
without the `test` prefix every sibling uses (`programSessionsDebugHlCppJsAndFlash`), so the
"method name minus its `test` prefix" pattern cannot be applied uniformly. (c)
`"Test runner: gutter markers"` for `HaxeTestRunLineMarkerContributorTest` is prose that does
not map back to the identifier (`RunLineMarkerContributor`); the rule requires a 1:1 mapping.
Also at HaxeDebugSupportTest.java:39, `assertFalse(supportsProgramDebug(INTERP), "programs
have no interp lane")` lives inside `testSessionsAddInterpFlashAndNodeJs`, whose display name
promises only test-session facts — move it to the program-session test.
