# 20-tests-b

### should-fix — src/test/java/com/intellij/plugins/haxe/ide/references/HaxeStringLiteralLinkTest.java:141
The painted-with-attribute predicate `infos.stream().anyMatch(info -> info.forcedTextAttributesKey == HaxeSyntaxHighlighterColors.STRING_FILE_LINK)` is spelled out five times (lines 141, 174, 175, 298, 313-314) with only the key varying. Duplication rule: the same expression in 2+ places gets one named home. Extract a helper, e.g. `private boolean painted(TextAttributesKey key)` that runs `doHighlighting()` (or takes the info list) — each test then reads `assertFalse(painted(STRING_FILE_LINK), ...)`.

### minor — src/test/java/com/intellij/plugins/haxe/ide/references/HaxeStringLiteralLinkTest.java:32
Member order in a class with `@Test`s is constants, fields, setup/teardown, tests, helpers. Here the four helpers (`firstStringLiteral`, `singleReferenceOfType`, `hasLinkReference`, `resolvedFileTarget`, lines 32-68) sit above the tests. Same in `src/test/java/com/intellij/plugins/haxe/ide/HaxeLanguageLevelGatingTest.java:46-53` (`setLevel`/`doTestAtLevel` before the tests). Move helpers below the last test — the sibling `HaxeCompletionTypeTextTest` and `HaxeFqnFileBasedIndexTest` already follow the order.

### minor — src/test/java/com/intellij/plugins/haxe/ide/references/HaxeStringLiteralLinkTest.java:142
`assertEquals(false, painted, ...)` in four places (lines 142, 158, 299, 315) — the assertion states "equals false" instead of the fact. Use `assertFalse(painted, ...)` (add the static import); the class already uses `assertTrue` for the positive cases.

### minor — src/test/java/com/intellij/plugins/haxe/actions/HaxeTypeAddImportIntentionActionTest.java:80
The deprecated-API replacement (`CodeStyle.getSettings(project)` + `CodeStyleSettingsManager.getInstance(project).cloneSettings(currSettings)`) is now spelled identically here and in `HaxeCodeInsightFixtureTestCase.setTestStyleSettings` (line 247). Since the override lives in a subclass of that base, the get-and-clone step wants one home, e.g. a `protected CodeStyleSettings cloneCurrentSettings()` in the base that both `setTestStyleSettings` bodies call before applying their own indent options. Also, in both files the new `com.intellij.application.options.CodeStyle` import was dropped into the middle of the `com.intellij.psi` block (and `RecursionManager` in `HaxeMultiFileTestBase.java:18` after the `java.util` imports) — place platform imports with their group.

No other findings: the 28 `annotation.languagelevel` fixtures map 1:1 onto the gating tests, every new inlay/index/parsing fixture has a consuming test (`liveCompiler/` is consumed by `HaxeLiveCompilerIntegrationTest`), `@DisplayName`s follow the generated pattern, every `@Nested` class in `HaxeFqnFileBasedIndexTest` repeats `@RunInEdt(writeIntent = true)`, and the `CodeStyle.getSettings`/`cloneSettings` change correctly replaces the deprecated `CodeStyleSettingsManager.getSettings`/`clone()` pair.
