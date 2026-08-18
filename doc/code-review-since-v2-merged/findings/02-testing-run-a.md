# 02-testing-run-a

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestDebugRunner.java:114
`createCommandLine` is a chain of four `instanceof` tests on the same value
(`backend instanceof BrowserDebugBackend`, `HashLinkBackend hashLink`,
`HxcppIntellijBackend hxcpp`, `NodeTestDebugBackend node`) followed by a blind
`(InterpDapBackend)backend` cast for the fall-through. The checklist calls this
out explicitly: "a branch chain type-testing one value becomes a pattern
switch". Convert to `return switch (backend) { case HashLinkBackend hashLink ->
…; case InterpDapBackend interp -> …; }` — each arm then keeps its comment, and
the interp arm stops being an unchecked cast that ClassCastExceptions if a new
backend type is ever added.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestDebugRunner.java:203
`buildFileModule(configuration)` and `sourceDirectories(configuration)` are
duplicated verbatim in `HaxeTestFlashDebugRunner.java:103` and `:112` (same
bodies, same javadoc for the latter — only the `ReadAction` wrapping placement
differs), and `sourceDirectories` is spelled a third time in
`HaxeActionBeforeRunTaskProvider.java:376`. "The same expression spelled in 2+
places gets one named home" — both belong on one shared home (they key on
`HaxeTestRunConfiguration.getBuildFilePath()`, so the configuration class or a
small `HaxeTestRunConfigurations`-style helper is the natural owner), with both
runners calling it.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:166
`frameworkArguments` (166-181) and `singleRunCompileArguments` (520-548) assemble
the same thing twice: the identical `boolean liveReporting = hostedTests ||
HaxeBuildToolSettings.getInstance(project).isLiveTestReporting();` plus
`new ArrayList<>(framework.reportingArgs(suiteName, reporterClasspath(framework),
liveReporting))` — including a near-verbatim copy of the three-line "the hosted
lanes need the injected reporter" comment. On top of that,
`singleRunCompileArguments` (528-537) re-derives the lime `suiteName` /
`hostedTests` pair already computed in `limeCompileArguments` (126-130). Extract
one `reportingArguments(project, framework, suiteName, hostedTests)` and one
`suiteContext(project, buildFilePath)` returning the (suiteName, hostedTests)
pair; the two entry points then differ only in the filter arguments they append,
which is the only thing that actually differs.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:238
`nmeCompileArguments` hand-rolls the nme target-flag mapping
(`case "neko" -> NEKO; case "flash" -> FLASH; default -> CPP`) while `nmePlan`
(634) gets the very same mapping from `NmeProjects.targetArtifact(...).target()`
— and the lime side correctly delegates through `limeTarget` →
`LimeProjects.targetFor`. Domain knowledge belongs in its domain's class, not
spread across callers: the two spellings can silently diverge (a new nme target
added to `NmeProjects` would give the plan the right target while the suite
label and the `hostedTests` flag here still say "CPP"). Add/reuse an
`NmeProjects.targetFor(targetFlag)` and call it from both places.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:190
`limeSpelling` (190-202) and `nmeSpelling` (212-224) are the same
index-walking loop with the same three cases (`-D`, `-cp`, `--macro`) and the
same `++i` consumption, differing only in the replacement strings. One
`respell(List<String> plainArguments, Map<String, String> forms)` — or a small
record of the three forms per tool — removes a whole copied loop whose off-by-one
handling would otherwise have to be maintained twice.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:257
The root-suite label prefix `"Target: "` is spelled in four places —
`rootSuiteName` (257), `limeCompileArguments` (127), `nmeCompileArguments` (244)
and `singleRunCompileArguments` (530). It is a user-visible tree label whose
value must match across the four lanes (the converter's suite bookkeeping keys on
the name); give it one named home — a `suiteLabel(HaxeTarget target)` helper — so
the four cannot drift apart.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestEventsConverter.java:170
Two rules land on the same statement:
`boolean result = super.processServiceMessages(injectLocationHint(
rewriteWarningOnlyMessage(text), testsBuildFilePath), outputType, visitor);`
wraps onto a second line with a computed argument nested inside a computed
argument — extract `String rewritten = rewriteWarningOnlyMessage(text);` and
`String withHint = injectLocationHint(rewritten, testsBuildFilePath);` so the
call collapses to one line stating the intent. And 180-189 is four independent
`if (event.is("testStarted"))` / `("testFinished")` / `("testSuiteFinished")` /
`("testFailed")` tests on the same `event.kind()` — that is a dispatch on one
value and reads as a `switch (event.kind())`. (Secondary effect of the nesting:
each of `rewriteWarningOnlyMessage` and `injectLocationHint` re-runs `parseEvent`
on text this method already parsed at 145 — three regex parses per line of test
output; passing the parsed `TcEvent` in would remove two of them.)

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestEventsConverter.java:224
`escapeValue` (224-231) and `escapeAttribute` (289-294) are two TeamCity escapers
in the same class; `escapeAttribute` is exactly `escapeValue` minus the `\n`/`\r`
pairs, which a file path never contains. One escaper serves both callers —
delete `escapeAttribute` and call `escapeValue` from `injectLocationHint`, so the
escaping rules cannot drift apart between the two emission paths.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestEventsConverter.java:261
The one-argument `injectLocationHint(String text)` overload has no production
caller — its only references are in `HaxeTestEventsConverterTest` (the converter
itself always calls the two-argument form at 171). It forwards with a bound
`null` and adds nothing else, which is the "do not wrap a method in another
method that adds nothing" case plus consumer-less API. Delete it and let the
tests pass `null` explicitly, as `HaxeTestEventsConverterTest:31` already does
for the two-argument form.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestDebugRunner.java:80
`debuggablePlan(configuration)` is recomputed three times per debug launch —
`validate` (80), `createBackend` (89) and `createCommandLine` (113) — and each
call is a blocking read action running the full planner, which for lime/nme
shapes re-reads and re-parses the build file text and re-resolves the tool paths.
The base class calls the three hooks in sequence on the same configuration, so
the plan can be computed once in `validate` and cached in a field (or the base
extended to pass a per-execution context) rather than paying the planning cost
three times.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestConfigurationFactory.java:21
`HaxeBundle.message("haxe.test.configuration.name")` is evaluated in both
`getName()` (21) and `createTemplateConfiguration` (26). The same expression in
two places gets one home: `createTemplateConfiguration` should pass `getName()`,
so the displayed factory name and the template configuration's name cannot
diverge if the key is ever split.

### minor — src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestLaunchPlanner.java:632
`String appPath = content == null ? null : ProjectXmlParser.parseAppPath(content);`
carries a dead null branch: `appFile` two lines above is computed from the same
`content` and a null `appFile` already threw, so `content` is non-null here.
Drop the ternary and call `ProjectXmlParser.parseAppPath(content)` — the guard
suggests a nullability the code has already ruled out.
