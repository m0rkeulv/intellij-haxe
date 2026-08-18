# Should-fix table (62 findings)

Location → fix → category, ordered by category. Full rationale for each row is in
`findings/<chunk>.md` (chunk given per row). Categories:

- **correctness** — real behavior defect
- **package-move** — package-direction violation; all rows resolve via one relocation of the stores/scanner/type classes out of `v2/toolwindow`
- **duplication** — same expression/knowledge in 2+ places gets one named home
- **dead-code** — consumer-less API, dead imports/keys/resources/branches
- **structure** — class doing several jobs / method shape
- **docs-comments** — javadoc placement, narration, uncommitted cited docs, stale README
- **style** — FQN imports, pass-through wrappers
- **i18n** — user-visible text not in a bundle
- **deprecated-api** — deprecated platform API workaround
- **service-layering** — feature code bypassing `v2/display` services
- **build-config** — gradle task input gaps

| # | Location | Fix | Category | Chunk |
|---|---|---|---|---|
| 1 | `HaxeEnvironmentStore:118,191` | `clearContainer`/`putDefine` must call `notifyChanged()` (after mutation); drop caller-side `panel.refreshTree()` compensation | correctness | 03 |
| 2 | `HaxeDisplayClient:177` | Compiler-error payload misreported as "Malformed"; when parse fails and `response.hasError()`, report via `failureDetail` instead | correctness | 07 |
| 3 | `HaxeIsReferenceToUtil:32` | Dead `instanceof HaxeEnumExtractedValue` branch never matches; test `HaxeEnumExtractedValueReference` | correctness | 08 |
| 4 | `HaxeResolveChecks:118-124` | Same-package resolve path returns unnormalized PSI; wrap in `normalizeClassResult` like the import path | correctness | 08 |
| 5 | `HaxeUntypedParameterInference:50` | Static app-global `bindingCache` holds PSI strongly, cleared by a per-project listener; make it a project-level service like sibling caches | correctness | 11 |
| 6 | `HaxeRunConfigurationType:53` | Null `factoryId` resolves to the FIRST factory; put `LegacyHxcppConfigurationFactory` first (or correct the javadoc) so pre-group configs keep their flavour | correctness | 13 |
| 7 | `LegacyHxcppDebugProcess:437` | `subList(first, size()-1)` drops the deepest stack frame; bound is `size()` | correctness | 13 |
| 8 | `LegacyHxcppDebugProcess:278` | Stop-before-connect surfaces `SocketException` as an ERROR balloon; return quietly when sockets were closed by `stop()` | correctness | 13 |
| 9 | `HaxeModuleBuilder:60-68` | `assignSdk` pins the first Haxe SDK, overriding the user's chosen project SDK; use `inheritSdk()` | correctness | 14 |
| 10 | `HaxeTemplateStep.kt:176-185` | Wizard preselects `targets.first()`; seed from `HaxeTargetOptions.defaultChoice()` so it matches the tool window | correctness | 14 |
| 11 | `HaxeProjectSdkStep.kt:41` | `runCatching { sdksModel.apply() }` swallows `ConfigurationException`; log or surface it | correctness | 14 |
| 12 | `HxmlFileParser:109-111` | `targetForFlag` carries both `@NotNull` and `@Nullable`; delete the false `@NotNull`, move javadoc above annotations | correctness | 17 |
| 13 | `HxpEvaluator.hx:26-59` | Every `.hxp` evaluation leaks a temp dir with copies of user files; delete it on a finally-equivalent path | correctness | 22 |
| 14 | `v2/toolwindow/tree/HaxeBuildFileScanner` (+`HaxeBuildFile`, `HaxeBuildFileType`) | Move model-side scanner/type/record out of `toolwindow.tree` (→ `v2/buildsystem`); a dozen buildtools importers then point the right way | package-move | 01 |
| 15 | `v2/toolwindow` stores (+`HaxeBuildSettingsListener`, `HaxeTargetOptions`) | Move all five stores + listener topic + target options to a non-UI package (`v2/buildtools/settings` or `v2/config`) | package-move | 03 |
| 16 | `v2/buildtools/*` (whole package) | Same relocation, buildtools side — nearly every file imports `v2.toolwindow` stores/scanner/type | package-move | 04 |
| 17 | `HaxeDefineContextService` (+`HaxeLibrarySync`, `HaxeLimeProjectInfoService`, `HaxeNmeProjectInfoService`) | Same relocation, services side; plugin.xml registrations move with the classes | package-move | 05 |
| 18 | `HaxeCompilerDisplayService:31-35` (+`HaxeDisplayConfiguration:4`) | Same relocation, display side | package-move | 06 |
| 19 | `HaxeBuildFileInspector:7`, `HaxeBuildFileNavigation:3` | Same relocation, buildsystem side (`HaxeBuildFileType`'s Swing icon presentation stays UI-side) | package-move | 17 |
| 20 | `HaxeModuleDetection:8` (+`HaxeProjectSdkSetupValidator:33`) | Same relocation, util side | package-move | 18 |
| 21 | `HaxeToolWindowModelBuilder:385-411` | `defaultBuildCommand`/`presentableBuildCommand` re-spell the per-type dispatch and have drifted from `addDefaultActions`; reuse the `BUILD_ACTION` `ActionNode` | duplication | 01 |
| 22 | `HaxeServerConsoleWindowFactory:216-219` | "server id running" predicate spelled 3×; one home: `HaxeCompilationServerManager.isRunning(serverId)`/`runningPort(serverId)` | duplication | 01 |
| 23 | `HaxeInstallLibraryAction:33` | Extract haxelib install/notify into a non-action helper (`HaxelibInstaller` in buildtools); one home for the `"haxe.command"` group id (5 spellings) and the duplicated `GeneralCommandLine` scaffold | duplication | 02 |
| 24 | `HaxeCompileCommands:86-107,196-218` | Per-type dispatch spelled 4×; give `HaxeBuildFileType` (or a strategy) `defaultActionNames()`/`actionCommand(...)` | duplication | 04 |
| 25 | `HaxelibPathParser:51,66 + 40-47,81-87` | Extract `isErrorLine(String)` and `parseDefineMarker(String)`, each spelled twice | duplication | 04 |
| 26 | `HaxeNmeProjectInfoService:84` | Background-eval machinery (cache/in-flight/executor/drain ordering) copied from the Lime service; extract one generic scheduling skeleton | duplication | 05 |
| 27 | `HaxeDisplayConfiguration:71,82` | hxml knowledge spread across callers; reuse `HxmlFileParser.DEFINE_FLAGS`, move line parsing + reference expansion into the hxml domain | duplication | 06 |
| 28 | `HaxeFullyQualifiedMemberNameIndex:109-117` | 4th copy of the member-add dance; extract `addMemberOrParameter(...)` | duplication | 09 |
| 29 | `HaxeCallExpressionEvaluatorCacheService:107` | In-flight budget choreography spelled 2×; extract `computeWithinInFlightBudget(key, compute)` | duplication | 11 |
| 30 | `HaxeGenericResolver:152-184` | Three `add*Internal` copies each rebuilding an identical record; collapse to one shared 2-line helper taking the target list | duplication | 12 |
| 31 | `HaxeMemberModel:81-90` | `isDeclaredPublic` repeats `isPublic`'s first three terms; extract the shared predicate | duplication | 12 |
| 32 | `FlashRunConfiguration:131` (+`LegacyHxcppRunConfiguration:163`) | 4 copies of resolve-path-against-project; one `protected resolveAgainstProject(...)` in `DapRunConfigurationBase` | duplication | 13 |
| 33 | `HaxeNewProjectWizard.kt:56-105` | Re-spells `HaxeTemplateScaffold.createModule` + `writeAndRegister`, and inlines the Main.hx template text (bypassing the customizable `.ft`); call the scaffold | duplication | 14 |
| 34 | `HaxeTypeCreator:118` (+4 more files) | "Haxe Class"/"Haxe Interface"/… template names as literals in 7 sites; constants in `HaxeFileTemplateUtil` | duplication | 16 |
| 35 | `HaxeActionBeforeRunDialog:115-130`, `HaxeActionRunConfigurationEditor:116-134` | `refillActionCombo` duplicated line-for-line; one package-private helper in `v2/runconfig` | duplication | 17 |
| 36 | `HaxeStringLiteralLinkTest:141` | Painted-with-attribute predicate spelled 5×; extract `painted(TextAttributesKey)` helper | duplication | 20 |
| 37 | `HaxelibPathParser:61,78` | `parseClasspaths`/`parseVersion` have no production caller; delete or TODO | dead-code | 04 |
| 38 | `HaxeGeneratedDumpService:328` | `clearCaches()` has zero callers; wire into `HaxeCompilerCaches.clearAndRehighlight` (or delete) | dead-code | 06 |
| 39 | `HaxeResolver:27-75` | ~30 dead imports left by the `HaxeResolveChecks` extraction; optimize imports | dead-code | 08 |
| 40 | `HaxeComponentIndexData:26` | `visibilityInherited` written+serialized but never read; wire the query-side consumer or TODO/delete | dead-code | 09 |
| 41 | `CallExpressionArgumentModel:37` | `incomplete` flag consumer-less (and its two producers disagree); wire the promised re-evaluation or delete the field + taint brackets | dead-code | 11 |
| 42 | `HaxeGenericResolver:123` | Commented-out call left above its replacement; delete | dead-code | 12 |
| 43 | `HaxeBundle.properties:26` | `runner.configuration.name` key's only caller was deleted; delete the key | dead-code | 13 |
| 44 | `HaxeUnusedFieldInspection:9` (+3 more files) | Dead imports left by the `HaxeUsageSearch` switch; delete | dead-code | 16 |
| 45 | `HaxeBundle.properties` (many lines) | 46 v1-surface keys whose callers were deleted (haxe.run.*, haxe.configuration.*, target widget, …); delete | dead-code | 21 |
| 46 | `icons/Haxe_logo_13.svg`, `icons/Haxe_logo_gray.svg` | Added but unreferenced (only `Haxe_logo_gray_13.svg` is used); delete or wire | dead-code | 21 |
| 47 | `HaxeModuleLevelBuilder:117` (jps-plugin) | `RUNNER_ID` gate references the deleted `HaxeDebugRunner`; the debug-builder variant can never run — remove it or TODO the replacement | dead-code | 23 |
| 48 | `HxmlFileParser:44-58` | Out-params via one-element arrays + 8-param recursion; one private accumulator holder | structure | 17 |
| 49 | `HaxeToolWindowNodes:264-273` | `CompilationServerNode` javadoc stranded on `ServerFailureLink`; move it to its declaration | docs-comments | 01 |
| 50 | `HaxeClassInheritanceIndexer:22-26` (+`HaxeTypedefInheritanceIndexer`) | Duplicated narration javadoc ("we are not allowed…"); rewrite once as behavior | docs-comments | 09 |
| 51 | `HaxeLanguageFeatureAnnotator:27` | Cites `doc/haxe-language-levels.md`, which is untracked; commit it or drop the citation | docs-comments | 10 |
| 52 | `HaxeProjectGenerator.kt:22` (+`HaxeTemplateFiles.kt:11`) | Cites untracked `doc/project-templates-wizard.md`; commit or drop | docs-comments | 14 |
| 53 | `nmml.xsd:19-24` | Header carries authorship narration ("NOTE MLO", "help from Claude"); state the fact only | docs-comments | 21 |
| 54 | `tools/LimeProjectParser/README.md:60-64` | "Planned" section lists plugin integration that already shipped; move it out of Planned | docs-comments | 22 |
| 55 | `HaxeMethodModel:226` | `getReturnTypeCacheProvider` no longer a cache provider; inline into the lambda (or rename `computeReturnType`) | naming | 12 |
| 56 | `HaxeSourceRootsInitializer.kt:103,106` | FQN parameter types; import `MutableEntityStorage`/`VirtualFileUrlManager` | style | 04 |
| 57 | `HaxeTemplateFilesTest:149` | `project()` is a pass-through of the base's `getProject()`; delete | style | 19 |
| 58 | `ProjectXmlEvaluator.hx:148,219-220` (+`HxpEvaluator.hx:30`, `HxpRunner.hx:72`) | FQN `haxe.io.Path.*`/`haxe.Resource`/`haxe.Json` calls; import instead | style | 22 |
| 59 | `LegacyHxcppDebugProcess:344` (+121, 230-321) | Balloon-notification strings hard-coded; move to `HaxeDebuggerBundle` | i18n | 13 |
| 60 | `HaxeBuildToolsSettingsPanel:40` | Hand-subclassed `SimpleListCellRenderer` keeps the defect the deprecation warns about; use the `listCellRenderer` DSL builder | deprecated-api | 05 |
| 61 | `HaxeServerStatusPanel:131` | Direct `new HaxeDisplayClient(...).serverMemory(...)` from feature code; move the fetch into `HaxeCompilerDisplayService` | service-layering | 01 |
| 62 | `tools/LimeProjectParser/build.gradle.kts:39-41` | `buildParser`/`testParser` inputs omit `src/main/resources` though the jar embeds `HxpRunner.hx` — stale-jar risk; add `inputs.dir` | build-config | 22 |
