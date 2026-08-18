# 10-sdk-config-misc

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:20
The class javadoc says a `--next` chain "splits into isolated per-compilation blocks
through {@link #sections}", but `HxmlFileParser` has no `sections` member — the link
does not resolve. The splitting actually lives in
`HaxeBuildFileInspector.sectionContents` (PSI-driven, so it cannot live here). Point
the sentence at `{@code HaxeBuildFileInspector.sectionContents}`, the way
`sectionIds`' javadoc at line 134 already does.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileInspector.java:96,100
`if (key.equals("--next"))` / `if (key.equals("--each"))` respell the separator names
that `HxmlFileParser.SECTION_FLAGS` (HxmlFileParser.java:41) already owns, together
with the same "a trailing token belongs to the following section" rule its javadoc
documents. hxml domain knowledge should have one home in its domain class, not be
spread across callers; expose named predicates (`isNextSeparator` / `isEachSeparator`,
or the constant itself) from `HxmlFileParser` and call them here.

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/config/sdk/ui/HaxeRuntimeSettingsControls.kt:45
`pathDetectedExecutable` is a character-for-character duplicate of
`HaxeToolPathResolver.pathHit` (src/main/java/.../v2/buildtools/HaxeToolPathResolver.java:204)
— both `PathEnvironmentVariableUtil.findInPath(HaxeSdkUtilBase.getExecutableName(name))`
mapped to `absolutePath`, and both were added on this branch. One of them should
delegate: make `HaxeToolPathResolver` expose the PATH lookup publicly and have this
UI helper call it (the UI package may depend on buildtools, not the reverse).

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/config/sdk/ui/HaxeRuntimeSettingsControls.kt:110,120
The "this SDK entry is a Flex/AIR one" predicate
`it.sdkType.name.lowercase(Locale.ROOT).contains("flex")` is spelled twice — once in
`flexSdks()` and again in `openSdkEditor()` to filter the editor's result. Two copies
of the same matching rule drift apart the moment the match has to widen (e.g. "air").
Extract `private fun isFlexSdk(sdk: Sdk)` and use it in both places.

### should-fix — src/main/java/com/intellij/plugins/haxe/lang/psi/impl/HaxeMethodPsiMixinImpl.java:100
`return getModel() instanceof HaxeMemberModel member ? member.isDeclaredPublic() : isPublic();`
— `getModel()` is declared to return `HaxeMethodModel`, which extends
`HaxeMemberModel`, and its body (line 104) always assigns before returning, so the
`instanceof` is statically always true and the `isPublic()` arm is unreachable. The
pattern match reads as if some models are not member models. Reduce to
`return getModel().isDeclaredPublic();` and drop the now-unused `HaxeMemberModel`
import added at line 32.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:261
`/** One parse run's mutable state, shared across included files (first target flag
wins). */` no longer describes what the class does: this branch moved include
expansion out of `parse` into `flatten`, and the accumulator's `visitedIncludes` field
was deleted with it. `parse` now sees one already-merged text and never recurses.
Trim the comment to the part that is still true (first target flag wins).

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:231-244
`mainClass` repeats `parse`'s tokenising loop verbatim — same `content.lines().toList()`
walk, same `line.isEmpty() || line.startsWith("#")` skip, same
`line.split("\\s+", 2)` into `flag`/`value`. Two copies of the hxml line grammar will
drift (e.g. when quoted values or `-D x = y` need handling). Either extract the
per-line tokenisation both share into one private helper (a `record HxmlArgument(String flag,
String value)` yielded by a stream) or let `ParseAccumulator` capture the main class
during the single `parse` pass and read it off `HaxeBuildFileInfo`.

### minor — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:77,236
The two new `line.split("\\s+", 2)` uses carry no comment. Code style requires every
regex — explicitly including `split` — to say above it what it matches; the sibling
`parseDefine`/`parseLibrary` splits at lines 184 and 191 do exactly that
(`// "name" or "name=value"`). One line each: it separates the flag from the rest of
the line, which stays one argument.

### minor — src/main/kotlin/com/intellij/plugins/haxe/config/sdk/ui/HaxeRuntimeSettingsControls.kt:33
`FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()` is
`@ApiStatus.Obsolete` in 2026.2 — its javadoc says "Verbose and complicated name;
consider using `singleFile()` instead". Every other chooser in this repo already uses
the modern spelling (`HashLinkRunConfigurationEditor`, `HaxeAddBuildFileAction`,
`DapExecutableRunConfigurationEditorBase`, …); this new file is the only holdout.
Use `FileChooserDescriptorFactory.singleFile().withTitle(chooserTitle)`.

### minor — src/main/java/com/intellij/plugins/haxe/config/sdk/HaxeAdditionalConfigurable.java:105-112
The `setInheritedDefaults(...)` call carries a computed ternary as its first argument
and then wraps over four more lines of positional `String?` arguments — a wrapping
argument list should have its computed pieces named first, and four same-typed
positional arguments are easy to transpose silently. Name the first argument
(`String haxelib = bundledHaxelib != null ? bundledHaxelib : pathDetectedExecutable("haxelib");`)
so the call collapses. Note also that this bundled-haxelib-else-PATH rule is the same
one `HaxeToolPathResolver.inheritedRuntimeDefaults` encodes at lines 180-185; the
sibling panel takes an `InheritedRuntimeDefaults` record instead of four strings, and
one shape for the two panels would be cheaper to keep correct.

### minor — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileInspector.java:101,111
`sectionLines.get(sectionLines.size() - 1)` is evaluated in two places in the same
loop, and the second (line 111, the tail append) hides that it is the same "current
section" the `--each` branch just cleared. Hold a `List<String> currentSection` local
updated when a new section is pushed, and both sites read as what they are.

### minor — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileInspector.java:45,63
Two dead/weakened guards in the reworked entry points. `inspectSections` returns
`List.of(HaxeBuildFileInfo.EMPTY)` on every empty path, so it can never return an
empty list — `inspect`'s `sections.isEmpty() ? HaxeBuildFileInfo.EMPTY : ...` at line
45 is unreachable. And the `default ->` arm at line 63 replaced what used to be an
exhaustive enum switch (`HXML`, `OPENFL/LIME/NMML`, `HXP_PROJECT/HXP_SCRIPT`): a new
`HaxeBuildFileType` now silently yields `EMPTY` instead of failing to compile. List
`HXP_PROJECT, HXP_SCRIPT, HXML` explicitly and keep the switch exhaustive. While
there: the `psiFile == null` fallback at line 85 collapses a `--next` chain into one
section, which the method javadoc does not mention.

### minor — src/main/kotlin/com/intellij/plugins/haxe/config/sdk/ui/HaxeRuntimeSettingsControls.kt:73-82
Inside `listCellRenderer(emptyText) { ... }` the block is only invoked for non-null
values — the platform overload renders `emptyText` itself for null (see
`com/intellij/ui/dsl/listCellRenderer/builder.kt`). With `T` inferred as `String`,
`LcrRow.value` is non-null, so `val name = value; if (name != null)` never fails and
the implicit null path is unreachable. Drop the guard and render `value` directly.

### minor — src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:208,217
`DEBUG_FLAGS` and `MAIN_FLAGS` sit mid-file directly above their methods while
`INCLUDE_MARKER`/`SECTION_FLAGS` are at lines 33-41 and
`TARGET_FLAGS`/`DEFINE_FLAGS`/`LIBRARY_FLAGS`/`CLASSPATH_FLAGS` at lines 115-121 —
three separate constant clusters in one class. The flag sets are one group; keep them
together with their siblings so a reader adding a flag spelling finds them all.

### minor — src/test/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileSectionsTest.java:70-71
`assertEquals(1, HxmlFileParser.parse(section).libraries().size(), "the shared include
must reach every section: " + section);` computes its fact inside the assertion and
wraps onto a second line — exactly the case the test rules name. Lift
`int libraryCount = HxmlFileParser.parse(section).libraries().size();` above the
assert (the `HaxeBuildFileInfo info` local the sibling test at line 46 already uses is
the same shape).

### minor — src/main/java/com/intellij/plugins/haxe/lang/psi/stubs/factories/HaxeMethodStubFactory.java:69
`//Note: we can only check if declared as we can not access any other files while
indexing.` — comments state behaviour, not first person; drop "we"/"Note:" and the
narration, e.g. `// only the DECLARED visibility: indexing cannot read other files`.
Missing space after `//` as well. The constraint itself is already spelled out in
`HaxeMethod.isDeclaredPublic`'s javadoc, so a one-clause pointer is enough.
