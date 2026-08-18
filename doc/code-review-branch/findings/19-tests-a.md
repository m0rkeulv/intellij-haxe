# 19-tests-a

### should-fix — src/test/java/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateFilesTest.java:149
`private Project project() { return myFixture.getProject(); }` duplicates the base class:
`HaxeCodeInsightFixtureTestCase` already provides `protected Project getProject()` doing exactly
this (HaxeCodeInsightFixtureTestCase.java:209). This is a pass-through wrapper that adds nothing,
and the rule says to look in the test base before writing a private helper — sibling
`HaxeLiveCompilerIntegrationTest` calls the inherited `getProject()` directly. Delete `project()`
and use `getProject()`.

### minor — src/test/java/com/intellij/plugins/haxe/v2/display/HaxeLiveCompilerIntegrationTest.java:54
`myFixture.copyFileToProject("GenMacro.hx").getParent().findChild("build.hxml")` is a 3+ call
chain on one line (split rule), and it is a roundabout way to reach a file the test itself copied:
line 52's `myFixture.copyFileToProject("build.hxml")` already returns that VirtualFile and the
result is discarded. Capture the return value at line 52 and copy GenMacro.hx as a plain statement
— the parent/findChild hop and the `assertNotNull` guard both disappear.

### minor — src/test/java/com/intellij/plugins/haxe/v2/display/HaxeDisplayConfigurationTest.java:16
DisplayName Kind drifts across the one `v2/display` feature area: `"Display: configuration
pipeline"` here, `"Compiler services: ..."` in HaxeGeneratedCodePreviewSanitizeTest:9 and
HaxeGeneratedDumpServiceArgsTest:16, and `"Live compiler integration: ..."` in
HaxeLiveCompilerIntegrationTest:37. The rule is one generated pattern with the Kind taken from the
feature area — pick one Kind (CLAUDE.md names the area "Compiler services") for all four. Also,
the live test's subject "catalog, resolve and completion" is prose that does not map 1:1 back to
the class identifier; the subject should derive from the class name
(e.g. "Compiler services: live compiler integration (live)"). "configuration pipeline" likewise
adds "pipeline", which is not in `HaxeDisplayConfigurationTest`.

### minor — src/test/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeTargetOptionsTest.java:61
The predicate `choicesFor(type).stream().anyMatch(choice -> choice.id().equals("<id>"))` is
spelled four times in `testEachBuildSystemOffersItsOwnConfiguredList` (lines 61-71). Same
expression in 2+ places gets one named home: a private helper such as
`offersChoice(HaxeBuildFileType type, String id)` collapses the four pipelines and the four
named locals to four one-line asserts.

### minor — src/test/java/com/intellij/plugins/haxe/v2/compiler/settings/HaxeCompilerProjectSettingsTest.java:49
The XML round-trip dance — `XmlSerializer.serialize(getState())`, `XmlSerializer.deserialize(...,
State.class)`, `new <Settings>()`, `loadState(...)` — is copied verbatim eight times across this
chunk: three times in this class (lines 49-53, 65-69, 125-130) and once each in
HaxeBuildToolProjectSettingsTest:40, HaxeActiveBuildFileStoreTest:54, HaxeBuildFilesStoreTest:73,
HaxeCustomActionsStoreTest:66 and HaxeEnvironmentStoreTest:135. A helper duplicated across sibling
test classes belongs in a shared home — a tiny generic
`static <T> T roundTrip(Object state, Class<T> stateClass)` in a v2 test util (or at least a
per-class helper where the dance repeats three times) removes the copies.

### minor — src/test/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeTargetOptionsTest.java:21
`getBasePath()` returns `"/toolwindow/"` and HaxeTemplateFilesTest:19 returns `"/wizard/"`, but
neither directory exists under `src/test/resources/testData/` and neither class loads a fixture
file. The override is mandatory (abstract in the base) but the value is fed to
`setTestDataPath`/`VfsRootAccess` and silently points nowhere — a reader hunting for the fixtures
finds nothing. Point both at an existing directory, or create the directories when fixtures are
actually intended (HaxeLiveCompilerIntegrationTest's `/liveCompiler/` does exist and is used).

### minor — src/test/java/com/intellij/plugins/haxe/v2/buildtools/NmeProjectsTest.java:16
Method `test` prefix convention is mixed among the new plain JUnit 5 classes: NmeProjectsTest,
HaxeDisplayConfigurationTest, HaxeGeneratedCodePreviewSanitizeTest and
HaxeGeneratedDumpServiceArgsTest prefix every method with `test`, while the sibling plain classes
in the same packages (HaxelibPathParserTest, HxmlFileParserTest, the store and settings tests)
do not. The prefix only earns its place in fixture-based classes where the method name drives
fixture lookup (HaxeTargetOptionsTest, HaxeTemplateFilesTest, HaxeLiveCompilerIntegrationTest);
for the plain classes pick one convention — the prefix-less majority form.
