# 14-wizard

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeNewProjectWizard.kt:56-105
`Step.setupProject` re-spells the whole module-creation dance that `HaxeTemplateScaffold.createModule` already owns (baseData → `"${base.path}/${base.name}"`, `ensureSdk()`, builder name/contentEntryPath/moduleFilePath, `commit(project).firstOrNull()`), and `generateStarterFiles` re-spells `writeAndRegister`'s claim-active-slot logic (`if (activeStore.activeFilePath == null) setActiveFile(...)`). On top of that, the inline `MAIN_HX` text block is character-for-character the content of `fileTemplates/j2ee/Haxe Project Main.hx.ft`, so this wizard bypasses the user-customizable template that `HaxeProjectTemplatesFactory` advertises ("users can customize what the generator scaffolds" — but not this generator). Duplication rule: same expression/knowledge gets one named home. Fix: have `Step.setupProject` call `HaxeTemplateScaffold.createModule` + `writeAndRegister` with `HaxeTemplateFiles.starterMainHx(project, "Main")`; that also removes the behavioral drift where the template path defaults the container's Compile command and this path does not.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/wizard/HaxeModuleBuilder.java:60-68
`assignSdk` sets the module SDK to `getSdksOfType(...).get(0)` — the first Haxe SDK in the table — whenever any exists. Both wizards chain `HaxeProjectSdkStep`, whose `setupProject` has already set the user's chosen SDK as the *project* SDK by the time `commit → setupRootModel` runs; an explicit module SDK overrides the inherited project SDK, so a user with two Haxe SDKs who selects the second gets a module pinned to the first. Fix: `model.inheritSdk()` when the project SDK is a Haxe SDK (or always — `ensureSdk`/the step make the project SDK the right one), reserving the table lookup for the no-project-SDK case.

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateStep.kt:176-185
`LimeFamilyTemplateStep` preselects `targets.first()`, but `HaxeTargetOptions` exposes `defaultChoice(type)` precisely because "the framework's declared default (each target enum's DEFAULT) — reordering the configured list does not change it". Taking the first row means a user who reordered Settings | Haxe | Frameworks gets a different wizard default than the tool window, which resolves through the same options class. Fix: `propertyGraph.property(...)` seeded from `HaxeTargetOptions.defaultChoice(buildFileType())` (mapped to the local `TargetChoice`).

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeProjectGenerator.kt:22
The KDoc cites `doc/project-templates-wizard.md` (also cited from `HaxeTemplateFiles.kt:11`), but that file is untracked (`?? doc/project-templates-wizard.md`) — checklist: "Files cited by javadoc/comments (docs, READMEs) are committed." Commit the doc with the branch or drop the references.

### should-fix — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeProjectSdkStep.kt:41
`runCatching { sdksModel.apply() }` discards the result, silently swallowing the `ConfigurationException` that `apply()` throws. If applying fails (e.g. an SDK added through the combo's "Add SDK..." affordance is invalid), the selected SDK may never reach the jdk table, yet the next line still installs it as the project SDK — the user gets a broken project with no message. At minimum log the failure; better, let the exception surface so the wizard reports it.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateScaffold.kt:31
The expression `"${base.path}/${base.name}"` is spelled three times: here, `HaxeNewProjectWizard.kt:58`, and `createLocalHaxelibRepo` (line 95) — the repeated-expression rule wants one named home (e.g. a `contentRootFor(step)` helper on the scaffold). `createLocalHaxelibRepo` recomputing it also means the repo directory is created even when the selected template's module creation failed and returned null — `excludeFromModule` then silently no-ops; passing the contentRoot the caller already holds (or bailing when module creation failed) closes that gap.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateFiles.kt:26-38
The `HxmlTargetOption` labels are user-visible combo text ("HashLink (VM bytecode)", "Java (generated sources)", "Eval (interpreter)"...) hardcoded in code. The bundle rule covers anything the user reads; the target names proper are product names, but the English parentheticals are translatable UI text. Every other label in these wizards goes through `HaxeWizardBundle` — move these (e.g. `haxe.wizard.target.hashlink.vm=...`) or at least the descriptive suffixes.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateFiles.kt:15
`const val SOURCE_DIR: String = HaxeModuleBuilder.SOURCE_DIR` creates a second name for the same constant, and both spellings are live in sibling files (`HaxeNewProjectWizard.kt` uses `HaxeModuleBuilder.SOURCE_DIR`; the template steps use `HaxeTemplateFiles.SOURCE_DIR`). One name should win — either drop the alias or move the constant to one owner and reference it everywhere.

### minor — src/main/java/com/intellij/plugins/haxe/v2/wizard/HaxeModuleBuilder.java:50
`createContentRoot` builds the identical `HaxeWizardBundle.message("haxe.wizard.cannot.create.content.root", contentEntryPath)` in two branches (lines 50 and 56) — hoist it into one local above the try (repeated-expression rule). While there: the caught `IOException` is dropped; `ConfigurationException` carries only the generic message, losing the OS-level cause a user would need to act on.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard/HaxeTemplateStep.kt:92-96,136-138
The Flash stage fields (width/height/fps/color) and SWF version are free-text and concatenated raw into `--swf-header w:h:fps:color`; a non-numeric entry produces an hxml the compiler rejects. `LimeFamilyTemplateStep` guards the same shape of input with `toIntOrNull() ?: default` — do the same here (color additionally wants a hex check), or add field validation.

### minor — src/main/kotlin/com/intellij/plugins/haxe/v2/wizard (all new .kt files)
The KDoc across the new Kotlin files uses Javadoc tags — `{@code ...}` (HaxeProjectGenerator, HaxeTemplateFiles, HaxeTemplateScaffold, HaxeProjectSdkStep, HaxeTemplateStep) and `{@link HaxeModuleBuilder}` (HaxeTemplateScaffold:22) — which KDoc does not render; they show up literally in quick-doc. Use backticks and `[HaxeModuleBuilder]` references.
