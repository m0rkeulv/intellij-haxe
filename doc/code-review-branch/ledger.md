# Branch review ledger — develop...HEAD

Generated 2026-08-09 from the 23 per-chunk findings files in `findings/`.
523 changed files (288 added / 165 modified / 6 renamed / 64 deleted), reviewed
against the CLAUDE.md Code style / Code review / Test code checklists.
Report-only: nothing has been fixed yet.

## Totals

| Severity | Count |
|---|---|
| fix-now | 3 |
| should-fix | 62 |
| minor | 112 |
| question | 8 |
| **total** | 185 |

## Per chunk

| Chunk | fix-now | should-fix | minor | question |
|---|---|---|---|---|
| [01-toolwindow-core](findings/01-toolwindow-core.md) | 0 | 5 | 8 | 0 |
| [02-toolwindow-actions-ui](findings/02-toolwindow-actions-ui.md) | 0 | 1 | 3 | 0 |
| [03-toolwindow-stores](findings/03-toolwindow-stores.md) | 1 | 2 | 4 | 0 |
| [04-buildtools-core](findings/04-buildtools-core.md) | 0 | 5 | 4 | 1 |
| [05-buildtools-services](findings/05-buildtools-services.md) | 0 | 3 | 7 | 1 |
| [06-display](findings/06-display.md) | 0 | 3 | 6 | 0 |
| [07-display-protocol-module](findings/07-display-protocol-module.md) | 0 | 1 | 7 | 0 |
| [08-lang-psi-resolver](findings/08-lang-psi-resolver.md) | 0 | 3 | 4 | 1 |
| [09-lang-psi-indexes](findings/09-lang-psi-indexes.md) | 0 | 3 | 7 | 1 |
| [10-annotators](findings/10-annotators.md) | 0 | 1 | 4 | 0 |
| [11-model-evaluator](findings/11-model-evaluator.md) | 0 | 3 | 3 | 0 |
| [12-model-core](findings/12-model-core.md) | 0 | 4 | 6 | 2 |
| [13-runner-debugger](findings/13-runner-debugger.md) | 0 | 6 | 5 | 1 |
| [14-wizard](findings/14-wizard.md) | 0 | 5 | 6 | 0 |
| [15-references-links](findings/15-references-links.md) | 1 | 0 | 4 | 0 |
| [16-ide-misc](findings/16-ide-misc.md) | 0 | 2 | 4 | 0 |
| [17-v2-compiler-runconfig-buildsystem](findings/17-v2-compiler-runconfig-buildsystem.md) | 1 | 4 | 6 | 0 |
| [18-util-common-misc](findings/18-util-common-misc.md) | 0 | 1 | 6 | 0 |
| [19-tests-a](findings/19-tests-a.md) | 0 | 1 | 6 | 0 |
| [20-tests-b](findings/20-tests-b.md) | 0 | 1 | 3 | 0 |
| [21-resources-config](findings/21-resources-config.md) | 0 | 3 | 4 | 1 |
| [22-lime-parser-tool](findings/22-lime-parser-tool.md) | 0 | 4 | 4 | 0 |
| [23-cross-cutting](findings/23-cross-cutting.md) | 0 | 1 | 1 | 0 |

## fix-now (full text)

### fix-now — src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeActiveBuildFileStore.java:59,69 (also HaxeEnvironmentStore.java:90,131,146,160,177,201; HaxeTargetSelectionStore.java:60,73)
Every mutator publishes BEFORE it mutates: `setActiveFile` runs `notifyChanged();` and only then `state.activeFile = filePath;` (same pattern in every setter and in all three `loadState` overrides, where the notification even precedes `this.state = state`). The topic is a `syncPublisher`, so listeners execute while the store still holds the old value: `HaxeDefineContextService.buildSettingsChanged()` nulls its snapshot and schedules `refreshAsync()` immediately — the background recompute can read the pre-change state on a pooled thread and is never re-triggered after the assignment lands, leaving a stale define context; the tool window's synchronous `refreshTree` subscriber rebuilds from old state too. Swap the order in every mutator: mutate first, publish last.


### fix-now — src/main/java/com/intellij/plugins/haxe/ide/references/HaxeStringLinkCompletionConfidence.java:36
The class overrides the deprecated `CompletionConfidence.shouldSkipAutopopup(PsiElement, PsiFile, int)`. Verified against the 2026.2 platform sources: that overload is `@Deprecated` with javadoc *"use shouldSkipAutopopup(Editor, PsiElement, PsiFile, int). It provides information about the current editor."* Hard rule: no `@Deprecated` platform API introduced, and the javadoc names the replacement. Fix: override the four-arg overload taking `Editor` (the editor parameter can simply be ignored); the rest of the body is unchanged.


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


## should-fix (locations — full text in each chunk file)

- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeBuildFileScanner.java:31 (also HaxeBuildFile.java:9, HaxeBuildFileType.java:11)
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerStatusPanel.java:131
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/tree/HaxeToolWindowNodes.java:264-273
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeToolWindowModelBuilder.java:385-411
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeServerConsoleWindowFactory.java:216-219
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/actions/HaxeInstallLibraryAction.java:33
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/HaxeEnvironmentStore.java:118,191
- src/main/java/com/intellij/plugins/haxe/v2/toolwindow/ (package placement of all five stores + HaxeBuildSettingsListener + HaxeTargetOptions)
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/ (package direction, whole chunk)
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxelibPathParser.java:61,78
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeCompileCommands.java:86-107,196-218
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxelibPathParser.java:51,66 and 40-47,81-87
- src/main/kotlin/com/intellij/plugins/haxe/v2/buildtools/HaxeSourceRootsInitializer.kt:103,106
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeDefineContextService.java:15 (also HaxeLibrarySync.java:18, HaxeLimeProjectInfoService.java:21, HaxeNmeProjectInfoService.java:16)
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeNmeProjectInfoService.java:84
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/settings/ui/HaxeBuildToolsSettingsPanel.java:40
- src/main/java/com/intellij/plugins/haxe/v2/display/HaxeGeneratedDumpService.java:328
- src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerDisplayService.java:31-35
- src/main/java/com/intellij/plugins/haxe/v2/display/HaxeDisplayConfiguration.java:71,82
- display-protocol/src/main/java/com/intellij/plugins/haxe/display/client/HaxeDisplayClient.java:177
- src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeIsReferenceToUtil.java:32
- src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolver.java:27-75
- src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolveChecks.java:118-124
- src/main/java/com/intellij/plugins/haxe/lang/psi/indexes/filebased/data/HaxeComponentIndexData.java:26
- src/main/java/com/intellij/plugins/haxe/lang/psi/indexes/filebased/indexer/HaxeClassInheritanceIndexer.java:22-26
- src/main/java/com/intellij/plugins/haxe/lang/psi/indexes/filebased/extension/fqn/HaxeFullyQualifiedMemberNameIndex.java:109-117
- src/main/java/com/intellij/plugins/haxe/ide/annotator/semantics/HaxeLanguageFeatureAnnotator.java:27
- src/main/java/com/intellij/plugins/haxe/model/evaluator/callexpression/CallExpressionArgumentModel.java:37
- src/main/java/com/intellij/plugins/haxe/model/evaluator/HaxeUntypedParameterInference.java:50
- src/main/java/com/intellij/plugins/haxe/model/evaluator/HaxeCallExpressionEvaluatorCacheService.java:107
- src/main/java/com/intellij/plugins/haxe/model/type/HaxeGenericResolver.java:123
- src/main/java/com/intellij/plugins/haxe/model/type/HaxeGenericResolver.java:152-184
- src/main/java/com/intellij/plugins/haxe/model/HaxeMemberModel.java:81-90
- src/main/java/com/intellij/plugins/haxe/model/HaxeMethodModel.java:226
- src/main/java/com/intellij/plugins/haxe/runner/HaxeRunConfigurationType.java:53
- src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:437
- src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:278
- src/main/resources/messages/HaxeBundle.properties:26
- src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:344
- src/main/java/com/intellij/plugins/haxe/runner/debugger/flash/FlashRunConfiguration.java:131
- src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeNewProjectWizard.kt:56-105
- src/main/java/com/intellij/plugins/haxe/v2/wizard/HaxeModuleBuilder.java:60-68
- src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateStep.kt:176-185
- src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeProjectGenerator.kt:22
- src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeProjectSdkStep.kt:41
- src/main/java/com/intellij/plugins/haxe/ide/quickfix/typedialog/HaxeTypeCreator.java:118
- src/main/java/com/intellij/plugins/haxe/ide/inspections/HaxeUnusedFieldInspection.java:9
- src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:109-111
- src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HaxeBuildFileInspector.java:7, HaxeBuildFileNavigation.java:3
- src/main/java/com/intellij/plugins/haxe/v2/buildsystem/HxmlFileParser.java:44-58
- src/main/java/com/intellij/plugins/haxe/v2/runconfig/HaxeActionBeforeRunDialog.java:115-130, HaxeActionRunConfigurationEditor.java:116-134
- src/main/java/com/intellij/plugins/haxe/util/HaxeModuleDetection.java:8
- src/test/java/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateFilesTest.java:149
- src/test/java/com/intellij/plugins/haxe/ide/references/HaxeStringLiteralLinkTest.java:141
- src/main/resources/messages/HaxeBundle.properties (multiple lines)
- src/main/resources/icons/Haxe_logo_13.svg, src/main/resources/icons/Haxe_logo_gray.svg
- src/main/resources/schema/nmml/nmml.xsd:19-24
- tools/LimeProjectParser/build.gradle.kts:39-41
- tools/LimeProjectParser/README.md:60-64
- tools/LimeProjectParser/src/main/haxe/limeparser/HxpEvaluator.hx:26-59
- tools/LimeProjectParser/src/main/haxe/limeparser/ProjectXmlEvaluator.hx:148,219-220
- jps-plugin/src/main/java/org/jetbrains/jps/haxe/build/HaxeModuleLevelBuilder.java:117

## question (locations)

- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeContextHealth.java:62
- src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeDefineContextService.java:147
- src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeIsReferenceToUtil.java:53-59
- src/main/java/com/intellij/plugins/haxe/lang/psi/indexes/unified/HaxeClassNameUnifiedIndex.java:116-121
- src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:253-267, src/main/java/com/intellij/plugins/haxe/model/HaxeStdPackageModel.java:110-120
- src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:153-168
- src/main/java/com/intellij/plugins/haxe/runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:97
- gradle.properties:38
