# Minor-findings triage

Classification of every `### minor` finding in findings/*.md against the current code.
Classes: MOOT (code gone/rewritten), FIXED (later fix addressed it), VALID-worthwhile (real gain), VALID-noise (taste-level churn).

## Summary

112 minor findings triaged (chunks 01-23), each verified against the current tree (package moves followed to the new locations):

| classification | count |
|---|---|
| MOOT | 0 |
| FIXED | 3 |
| VALID-worthwhile | 68 |
| VALID-noise | 41 |

No finding was MOOT — the criticized code moved (stores → `v2/buildtools/settings`, scanner/types → `v2/buildsystem`) but survived intact, so findings were re-checked at the new homes. The three FIXED items fell out of the duplication extractions (HaxeCommandNotifications, HaxeProjectInfoCache.firstErrorLine) and a test-assert cleanup.

### Top VALID-worthwhile (best value first)

1. **HaxeGeneratedCodePreview static previews cache never cleared** (06, M) — unbounded growth of library-sized LightVirtualFiles over the IDE process lifetime; unreachable by Purge Caches.
2. **HxpEvaluator drains stdout to EOF before stderr** (22, S) — pipe-buffer deadlock on a large error dump; hangs the tool (60s timeout from the IDE, forever standalone).
3. **HaxeDefineContextService constructor subscribes `this` before `this.project` is assigned** (05, S) — NPE window on a background buildSettingsChanged.
4. **HaxeBuildToolsConfigurable.apply() stops every compilation server unconditionally** (05, S) — an unrelated path edit throws away every warm server cache.
5. **HaxeDefineIntention.isAvailable hard-coded true** (16, S) — intention offered in projects where clicking it does nothing.
6. **HaxeToolWindowTreeRenderer.isBuildAction mixes equals/equalsIgnoreCase** (01, S) — "Compile" gets a different icon than "Build"/"Clean"; actual behavior inconsistency.
7. **HaxeTemplateStep SWF width/height/fps/color concatenated unvalidated into --swf-header** (14, S) — a non-numeric entry generates an hxml the compiler rejects; sibling Lime step already guards.
8. **HaxeStandardAnnotation computes "level before X" via raw ordinal-1** (10, S) — AIOOBE on the floor constant; belongs on HaxeLanguageLevel.previous().
9. **usageState != USED predicate + twin comment spelled in 4 unused-inspections** (16, S) — one isConsideredUsed home carries the subtle comment once.
10. **isInGeneratedPreview+isValid guard pair stamped into ~22 annotators** (10, S) — one shouldSkip(element) helper, one place for the next global skip condition.
11. **fullyResolveTypedef false @Nullable on a dereferenced parameter** (18, S) — wrong nullability contract on a widely used util, plus vague TODO and broken indentation in the moved block.
12. **Dead-API sweep: HaxeLanguageLevel.getMajor, getEffectiveLanguageLevel(Module), Registry.port, client hover/definition without TODO, HAXE_LOGO_GRAY_13, fileDescriptor offset param, FqnIndexUtil fqn param** (06/07/17/21/01/09, S each) — mechanical deletes per the consumer-less-API checklist item.

## Detail table

| chunk | location | one-line summary | classification | reason/effort |
|---|---|---|---|---|
| 01 | v2/toolwindow/tree/HaxeToolWindowTreeRenderer.java:209 | isBuildAction mixes equals/equalsIgnoreCase — "Compile" gets wrong icon | VALID-worthwhile | still verbatim; actual behavior inconsistency, S |
| 01 | v2/toolwindow/tree/HaxeToolWindowTreeRenderer.java:213 | groupLabel duplicates GroupNode.speedSearchText mapping; " ▾" spelled 5x | VALID-worthwhile | both duplications still present; one home each, S |
| 01 | v2/toolwindow/HaxeServerConsoleWindowFactory.java:132 | nested ternary wrapping inside content.setIcon(...) | VALID-noise | still present; formatting taste, S |
| 01 | v2/toolwindow/HaxeToolWindowPanel.java:216,557 | nested lambda in refreshTree onSuccess; 3-term stream filter inline | VALID-noise | still present; style-rule restatement on short code, S |
| 01 | v2/toolwindow/HaxeToolWindowPanel.java:464-468 | node.getUserObject() re-evaluated per branch instead of one local/pattern switch | VALID-noise | still present; trivial, S |
| 01 | v2/toolwindow/HaxeToolWindowNavigation.java:54 | fileDescriptor offset param only ever called with 0 | VALID-worthwhile | still true (all 4 callers pass 0); dead param misleads, S |
| 01 | v2/toolwindow/HaxeServerStatusPanel.java:115 | javadoc says "no-op while server down" but down path renders unavailable text | VALID-worthwhile | still verbatim; misleading comment, S |
| 01 | HaxeToolWindowPanel.java:104, buildFileNode, HaxeBuildFileScanner.java:77-78 | stray double blank lines | VALID-noise | still present (scanner now in v2/buildsystem); pure formatting, S |
| 02 | v2/toolwindow/actions/HaxeExecuteCommandAction.java:43 | fully-qualified icons.HaxeIcons.HAXE_LOGO instead of import | VALID-worthwhile | still present; codified no-FQN rule, sibling files import it, S |
| 02 | v2/toolwindow/actions/HaxeExecuteCommandAction.java:51-52 | arguments.trim() evaluated twice in consecutive lines | VALID-noise | still present; trivial repeated cheap call, S |
| 02 | v2/toolwindow/actions/DefinePrompt.java:24 | hand-rolled whitespace regex vs StringUtil.containsWhitespaces | VALID-noise | still present but regex now carries its comment; micro gain, S |
| 03 | v2/buildtools/settings/HaxeCustomActionsStore.java:29-30 | ContainerActions keyed by ownerId which actually holds a build-file path; siblings use containerId | VALID-worthwhile | still verbatim after package move; confusing naming, S |
| 03 | v2/buildtools/settings/HaxeBuildFilesStore/HaxeCustomActionsStore/HaxeEnvironmentStore | find/getOrCreate pair spelled 3x, sanitize loop 2x | VALID-noise | still present in all three; generic helper costs more than the dupes, M |
| 03 | v2/buildtools/settings/HaxeTargetOptions.java:34,59 | TargetChoice id and displayName identical at every construction site | VALID-worthwhile | still verbatim after move; dead-weight field confuses consumers — collapse or TODO, S |
| 03 | v2/toolwindow/HaxeCommandRunner.java:59-60 | createNotification argument list wraps — extract title local | FIXED | now extracts String title and one-line HaxeCommandNotifications.notify |
| 04 | v2/buildtools/HaxeProjectTaskRunner.java:41 | @CustomLog generates a log field nothing uses | VALID-worthwhile | still present, zero log calls; dead annotation, S |
| 04 | v2/buildtools/HaxeSourceRootsInitializer.kt:107 | removePrefix("file://") hand-rolls VfsUtilCore.urlToPath | VALID-worthwhile | still present; platform API also normalizes separator, S |
| 04 | v2/buildtools/HaxeSourceRootsInitializer.kt:53 | WorkspaceModel currentSnapshot fetched inside per-module loop | VALID-noise | still present; cheap call, micro-opt, S |
| 04 | v2/buildtools/HaxeModuleWorkspace.kt:50 | multi-line ModuleEntity construction buried inside addEntity(...) | VALID-noise | still present; formatting taste, S |
| 05 | v2/buildtools/Haxe{Lime,Nme}ProjectInfoService | stderr-first-line expression spelled 3x inline in log.warn | FIXED | extracted to HaxeProjectInfoCache.firstErrorLine, used at all 3 sites |
| 05 | v2/buildtools/HaxeDefineContextService.java:172 | cacheKey re-spells document-or-file stamp instead of calling own contentStamp() | VALID-worthwhile | still present; helper exists in same class, S |
| 05 | v2/buildtools/settings/ui/HaxeBuildToolsSettingsPanel.java:139 | @ApiStatus.Obsolete createSingleFileNoJarsDescriptor vs singleFile() | VALID-worthwhile | still present; one-token modernization per platform javadoc, S |
| 05 | v2/buildtools/HaxeCompilationServerManager.java:257 | broadcast(...) wraps with computed message buried in call | VALID-noise | still present; formatting taste, S |
| 05 | v2/buildtools/settings/HaxeFrameworkTargetSettings.java:139-145 | identical TargetDefinition mapping lambda 3x + second per-framework switch | VALID-worthwhile | still present; shared interface on the three target enums collapses both switches, M |
| 05 | v2/buildtools/settings/ui/HaxeBuildToolsConfigurable.java:95 | apply() stops compilation server even when only unrelated paths changed | VALID-worthwhile | still unconditional; throws away warm caches — gate on server/SDK fields, S |
| 05 | v2/buildtools/HaxeDefineContextService.java:46-47 | constructor subscribes this to message bus before this.project assigned | VALID-worthwhile | still subscribe-then-assign; NPE window in refreshAsync, S |
| 06 | v2/display/HaxeCompilerMetadataService.java:38 | Registry record's port component written but never read | VALID-worthwhile | still present (only write at :104, no .port() reads); dead data, S |
| 06 | v2/display/HaxeCompilerResolveService.java:134 | narrating "check if we got..." comment with "we" | VALID-noise | still verbatim; comment-style nit, S |
| 06 | v2/display/HaxeCompilerResolveService.java:257-262 | fixed extern header rendered via six chained appends instead of text block | VALID-noise | still present; formatting/style, S |
| 06 | v2/display (5 files) | getOriginalFile().getVirtualFile() spelled 5x incl. two private fileOf twins | VALID-worthwhile | all 5 sites still present; one package helper, S |
| 06 | v2/display/HaxeCompilerProblemMarker.java:54 | single-thread local map declared ConcurrentHashMap | VALID-noise | still present; implies sharing that doesn't exist, micro, S |
| 06 | v2/display/HaxeGeneratedCodePreview.java:61 | static previews cache never cleared, unreachable by Purge Caches | VALID-worthwhile | still static ConcurrentHashMap, no clearCaches; unbounded growth over IDE lifetime, M |
| 07 | display-protocol .../DisplayJsonTest.java:24 | fully-qualified java.util.Map.of in test | VALID-worthwhile | still present; codified no-FQN rule, S |
| 07 | display-protocol .../HaxeDisplayClient.java:93,98 + DisplayMethods | hover/definition/batch-diagnostics and 11 method constants have no consumer and no TODO | VALID-worthwhile | still zero plugin callers, no TODO markers; checklist item on consumer-less API, S |
| 07 | display-protocol .../HaxeDisplayClient.java:18 | class javadoc claims "stateless" but mutable observer field exists | VALID-worthwhile | still verbatim; misleading comment, S |
| 07 | display-protocol .../LiveDisplayServerTest.java:249 | waitUntilAccepting checks deadline/sleep only in catch — empty-success spins forever | VALID-worthwhile | still catch-only; suite-hang risk on wedged server, S |
| 07 | display-protocol .../JsonTypeRef.java:53 | Stream.of(node).flatMap(valueStream) instead of node.valueStream() | VALID-noise | still present; one-expression rewrite, S |
| 07 | display-protocol .../HaxeDisplayClient.java:113,136,144 | wrapping Map.of bodies nested inside rpc(...) calls | VALID-noise | metadata/module/typeBlueprint still wrap (hover now uses positionParams helper); formatting, S |
| 07 | display-protocol/build.gradle.kts:15 | module comment claims a "type-blueprint cache" that lives elsewhere | VALID-worthwhile | still verbatim; misleading comment, S |
| 08 | lang/psi/HaxeResolveChecks.java:1246 | scripted move corrupted comment: "typedefs can omit HaxeResolveChecks." | VALID-worthwhile | still verbatim; nonsense comment, S |
| 08 | lang/psi/HaxeResolveChecks.java:54-55 | class javadoc typos ("to try to ma HaxeResolver class simpler", "a references") | VALID-worthwhile | still verbatim; garbled public javadoc, S |
| 08 | lang/psi/HaxeResolveChecks.java:613 | empty if-statement with unused pattern variable | VALID-worthwhile | still verbatim; dead code, S |
| 08 | lang/psi/impl/HaxeReferenceImpl.java:882,924,939 | null-asResolveResult guard + comment pasted three times | VALID-worthwhile | all three sites still present; extract helper, S |
| 09 | lang/psi/stubs/serializers/HaxeClassStubSerializer.java:60-72 | typedef/class branches are twin loops differing only in stub index key | VALID-worthwhile | still verbatim; name the key, single loop, S |
| 09 | lang/psi/indexes/filebased/indexer/HaxeFullyQualifiedNameIndexer.java:21+ | indentation drift, missing brace spaces, stray blank lines, enum placement | VALID-noise | still present; pure formatting, S |
| 09 | lang/psi/indexes/unified/HaxeClassNameUnifiedIndex.java:62-64 | lambda nesting a block-bodied supplier lambda in completion pipeline | VALID-noise | still present; style-rule restatement, S |
| 09 | lang/psi/indexes/unified/fqn/HaxeFullyQualifiedClassNameUnifiedIndex.java:41 | getAllKeys bypasses compiler-index facade, calls catalog service directly | VALID-worthwhile | still present; sibling index goes through the leg — inconsistent layering, S |
| 09 | HaxeClassFieldNameFileIndex.java:69 / HaxeClassNameFileIndex.java:76 | three variants of the keep-iterating comment, two truncated | VALID-noise | both truncated variants still present; comment wording, S |
| 09 | lang/psi/indexes/filebased/extension/HaxeClassNameFileIndex.java:78-81 | three consecutive blank lines left by deleted block | VALID-noise | still present; formatting, S |
| 09 | lang/psi/indexes/filebased/extension/fqn/HaxeFqnIndexUtil.java:25 | resolveModule takes an fqn parameter its body never reads | VALID-worthwhile | still present and unused; dead param across 3 callers, S |
| 10 | ide/annotator/HaxeStandardAnnotation.java:85 | "level before X" via raw ordinal arithmetic; AIOOBE on first constant | VALID-worthwhile | still verbatim; belongs on HaxeLanguageLevel as previous(), S |
| 10 | ide/annotator/semantics/HaxeAbstractClassAnnotator.java:47-52 | getModel() re-evaluated 2-3x per branch | VALID-noise | still present; extract-local style, S |
| 10 | ide/annotator/color/HaxeStringLinkColorAnnotator.java:21-23 | javadoc claims "no resolution happens here" but body gates on lastSegment.resolve() | VALID-worthwhile | still contradicts :49; misleading comment, S |
| 10 | ide/annotator/semantics/* (~20 files) | identical isInGeneratedPreview+isValid guard pair stamped in every annotate() | VALID-worthwhile | 22 files still carry the pair; one shouldSkip(element) helper, S |
| 11 | model/evaluator/callexpression/HaxeCallExpressionUtil.java:63-80 vs 96-110 | hole overload duplicates whole body of createContextForMethodCall | VALID-worthwhile | dup still present (incomplete-asymmetry since fixed); delegate with -1, S |
| 11 | model/evaluator/callexpression/HaxeCallExpressionUtil.java:426-478 | per-argument loop body duplicated between call- and new-expression getArgumentList | VALID-worthwhile | still duplicated (drift partly healed, comment cross-references); shared helper, S |
| 11 | model/evaluator/HaxeUntypedParameterInference.java:144,164 | multi-term booleans doing work inside if-heads | VALID-noise | still present; extract-local style, S |
| 12 | model/HaxePackageModel.java:37,193 | @jspecify NonNull auto-import instead of jetbrains @NotNull | VALID-worthwhile | still present; wrong annotation library, inconsistent with codebase, S |
| 12 | model/HaxePackageModel.java:208-220 | directory-walk lookup block spelled twice in findFileByDirectoryWalk | VALID-worthwhile | still duplicated; extract helper, S |
| 12 | model/HaxeProjectModel.java:139,154 | sdk-in-scope predicate re-spelled with inverted null handling | VALID-noise | still present; extract-local style, S |
| 12 | model/type/HaxeGenericResolver.java:113-114,150 | stray blank-line runs between methods | VALID-noise | still present; formatting, S |
| 12 | model/HaxePackageModel.java:33-37 (+5 siblings) | new imports appended outside sorted import group | VALID-noise | still present; cosmetic import churn, S |
| 12 | model/HaxePackageModel.java:176 | trailing-whitespace-only line | VALID-noise | still present; formatting, S |
| 13 | runner/debugger/dap/ide/DapRunConfigurationBase.java:19 (+DapCommandLineRunningState:15) | javadoc claims "every DAP-debugger" base but Flash/LegacyHxcpp (non-DAP) extend it | VALID-worthwhile | both javadocs unchanged, both non-DAP subclasses still extend; misleading name/docs, M |
| 13 | runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:725 | Pattern.compile of constant pattern on every evaluation failure | VALID-noise | still present; micro-opt on a rare path, S |
| 13 | runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:392 | replaceAll regex for plain char swap vs replace('.','/') | VALID-noise | still present; micro, S |
| 13 | runner/debugger/hxcpp/legacy/LegacyHxcppDebugProcess.java:360 | "Could verify..." deferred-work comment without TODO marker | VALID-worthwhile | still verbatim; codified TODO rule, failed delete passes silently, S |
| 13 | runner/debugger/dap/ide/FqnNavigateLink.java:29 | hard-coded user-visible "Resolving" progress title (+ double space) | VALID-worthwhile | still verbatim; bundle rule for user-visible text, S |
| 14 | v2/wizard/HaxeTemplateScaffold.kt:31,95 | contentRoot expression spelled twice; repo dir created even when module creation failed | VALID-noise | third site (NewProjectWizard) gone with the should-fix; 2 nearby sites remain, edge-case gap, S |
| 14 | v2/wizard/HaxeTemplateFiles.kt:26-38 | HxmlTargetOption combo labels ("HashLink (VM bytecode)"...) hardcoded, not bundled | VALID-worthwhile | still hardcoded; bundle rule, every sibling label uses HaxeWizardBundle, S |
| 14 | v2/wizard/HaxeTemplateFiles.kt:15 | SOURCE_DIR alias of HaxeModuleBuilder.SOURCE_DIR — both names live in siblings | VALID-worthwhile | still aliased and both spellings in use; one name should win, S |
| 14 | v2/wizard/HaxeModuleBuilder.java:51,57 | identical bundle message built in two branches; caught IOException cause dropped | VALID-worthwhile | still present; lost OS-level cause is actionable info, S |
| 14 | v2/wizard/HaxeTemplateStep.kt:92-95,136-138 | SWF width/height/fps/color free text concatenated raw into --swf-header | VALID-worthwhile | still unguarded (Lime step uses toIntOrNull defaults); rejected-hxml risk, S |
| 14 | v2/wizard/*.kt (5 files) | KDoc uses Javadoc {@code}/{@link} tags that KDoc renders literally | VALID-worthwhile | still throughout; quick-doc shows raw tags — mechanical sweep, S |
| 15 | ide/references/HaxeStringLinkCompletionConfidence.java:41 | hand-rolls literalAtCaret/contentBeforeCaret that HaxeStringLiterals owns | VALID-worthwhile | still hand-rolled; package helper exists and siblings use it, S |
| 15 | ide/references/HaxeStringQnameCompletionContributor.java:70-100 | 50-line addCompletions doing three steps; lookup-element ternary duplicated | VALID-noise | still monolithic (3 addElement sites); extract-method taste, S |
| 15 | ide/references/HaxeStringLiteralReferenceContributor.java:70-72 | three-line multi-term else-if head vs sibling's named-shape pattern | VALID-noise | still present; extract-local style, S |
| 15 | ide/references/HaxeStringLiteralReferenceContributor.java:68 | List.of(array) intermediate instead of Collections.addAll | VALID-noise | still present; micro, S |
| 16 | ide/intention/HaxeDefineIntention.java:66-67 | isAvailable hard-coded true but invoke no-ops without active container | VALID-worthwhile | still returns constant true; intention offered where clicking does nothing, S |
| 16 | ide/inspections/HaxeUnused{Field,Function,LocalVar,Method}Inspection | usageState != USED predicate + twin comment spelled in 4 inspections | VALID-worthwhile | all 4 sites still present; one isConsideredUsed home, S |
| 16 | ide/quickfix/HaxeIntroduceType{InModule,NewFile}QuickFix.java:73/71 | dialog title "Unable to create haxe type" inline, not bundled | VALID-worthwhile | still inline at both sites; bundle rule, S |
| 16 | ide/generation/BaseHaxeGenerateAction.java:49 | lingering "TODO getData ... anymore?" question-mark TODO above new workaround | VALID-worthwhile | still verbatim; TODO states neither verified fact nor deferral, S |
| 17 | v2/runconfig/HaxeActionBeforeRunTaskProvider.java:255 | debugAdditions javadoc claims "Call in a read action" it doesn't need; dialog double-wraps | VALID-worthwhile | javadoc and dialog wrap (Dialog:99) both unchanged; stale contract, S |
| 17 | v2/runconfig/HaxeDebugAdditions.java + provider:253-296 | "debug additions" concept split — lime/nme cases + DESKTOP_CPP_TARGETS live in the before-run provider | VALID-worthwhile | still split; one home for the dispatch, M |
| 17 | v2/compiler/HaxeLanguageLevel.java:21 | VERSION_PATTERN regex has no what-it-matches comment | VALID-worthwhile | still missing; codified regex-comment rule, S |
| 17 | v2/compiler/HaxeLanguageLevel.java:31-33 | getMajor() has zero callers | VALID-worthwhile | still consumer-less (only HaxelibSemVer.getMajor calls elsewhere); dead code, S |
| 17 | v2/compiler/settings/HaxeCompilerSettings.java:63-65 | getEffectiveLanguageLevel(Module) default overload has no callers | VALID-worthwhile | still consumer-less; dead convenience API, S |
| 17 | v2/compiler/settings/ui/HaxeCompilerConfigurable.java:72-73 | publish+daemon-restart pair duplicated verbatim in HaxeLanguageLevelUtil:69-70 | VALID-worthwhile | still verbatim incl. same reason string; one named home, S |
| 18 | util/HaxeResolveUtil.java:1703-1748 | moved fullyResolveTypedef: false @Nullable on deref'd param, vague TODO, broken indentation | VALID-worthwhile | all three still present (deref at ~1724, TODO at 1744); wrong nullability misleads callers, S |
| 18 | util/HaxeResolveUtil.java:232-238 | moduleNameOf hand-rolls strip-extension vs FileUtil.getNameWithoutExtension | VALID-noise | still hand-rolled; micro, platform helper exists, S |
| 18 | common config/LimeTarget.java + OpenFLTarget.java (+NMETarget) | identical 14-entry lists and accessor boilerplate across three enums | VALID-worthwhile | still duplicated; list updates must happen twice in lockstep — pairs with the HaxeFrameworkTargetSettings finding, M |
| 18 | util/HaxeQnameResolveUtil.java:62-66 | three-step filter lambda instead of named predicate | VALID-noise | still present; style-rule restatement, S |
| 18 | HaxeFileType.java:62 (+HXMLFileType, HxpFileType) | mixed @NotNull/@jspecify @NonNull in one signature | VALID-worthwhile | still present in all three file types; wrong annotation lib, S |
| 18 | editor/HaxeRestoreReferencesDialog.java:46 | commented-out field declaration in edited field block | VALID-worthwhile | still present (plus commented ctor blocks); checklist dead-code item, S |
| 19 | v2/display/HaxeLiveCompilerIntegrationTest.java:54 | 3+ call chain re-reaching a file the test already copied | VALID-noise | still present; test tidy, S |
| 19 | v2/display test classes | DisplayName Kind drift: "Display:", "Compiler services:", "Live compiler integration:" in one feature area | VALID-worthwhile | drift still present; codified one-Kind-per-area convention, S |
| 19 | v2/buildtools/settings/HaxeTargetOptionsTest.java:60-72 | choicesFor+anyMatch predicate spelled 4x | VALID-noise | still 4 pipelines (with named booleans); test-local helper churn, S |
| 19 | settings/store tests (8 sites) | XmlSerializer round-trip dance copied 8x across sibling test classes | VALID-worthwhile | all 8 sites still present; shared roundTrip helper per test-base rule, S |
| 19 | HaxeTargetOptionsTest:20 / HaxeTemplateFilesTest:19 | getBasePath returns /toolwindow/ + /wizard/ which don't exist under testData | VALID-worthwhile | both still point nowhere; misleads fixture hunts, S |
| 19 | v2 plain JUnit5 tests (NmeProjectsTest etc.) | test-prefix convention mixed among plain non-fixture classes | VALID-noise | still mixed; naming taste, S |
| 20 | ide/references/HaxeStringLiteralLinkTest.java:32-68 (+HaxeLanguageLevelGatingTest) | helpers placed above tests against member-order rule | VALID-noise | helpers still on top; ordering convention, S |
| 20 | ide/references/HaxeStringLiteralLinkTest.java:142+ | assertEquals(false, ...) instead of assertFalse | FIXED | no assertEquals(false remains in the file |
| 20 | HaxeTypeAddImportIntentionActionTest.java:82 / test base:249 | get-and-clone CodeStyle settings duplicated between subclass and base; misplaced imports | VALID-noise | both sites still present; two-site dup, S |
| 21 | icons/HaxeIcons.java:64 | HAXE_LOGO_GRAY_13 field has zero consumers (plugin.xml uses raw path) | VALID-worthwhile | still unreferenced; wire plugin.xml to it or delete, S |
| 21 | CHANGELOG.md:2-14 | marketplace-rendered 2.0.0 entry: double spaces, missing bullet, "* - " pseudo-nesting | VALID-worthwhile | all defects still present; user-visible text, S |
| 21 | messages/HaxeBundle.properties:556 | orphan "# V2 new module wizard" section header with no keys | VALID-noise | still present; one-line delete, S |
| 21 | META-INF/plugin.xml:157,771-778 | double space in programRunner tag; five-blank-line run at end of actions | VALID-noise | both still present; whitespace, S |
| 22 | tools/LimeProjectParser HxpEvaluator.hx:65-66 | stdout drained to EOF before stderr — pipe-buffer deadlock on large error output | VALID-worthwhile | still sequential readAll; hang until 60s kill (forever standalone), S |
| 22 | LimeProjectParser IntegrationMain.hx:149 / HxpEvaluator.hx:101 | createTempDirectory copied verbatim; name=value split spelled twice | VALID-noise | both dups still present; small tool-local dup, S |
| 22 | LimeProjectParser Main.hx:5,15 | class doc and usage line omit accepted --haxe/--haxelib flags | VALID-worthwhile | parser still accepts both (:42-44), docs still omit; misleading usage, S |
| 22 | LimeProjectParser ProjectXmlEvaluator.hx:149-150,156 | nested multi-line ternary; default-case comment still lists "app" which has its own case | VALID-worthwhile | both still present (FQN part since fixed); wrong comment, S |
| 23 | haxelib/HaxeLibrary.java:48 | TODO cites deleted LimeUtil.getLimeProjectModel() as example | VALID-worthwhile | still verbatim; dangling reference — point at HxmlFileParser, S |
