# 18-util-common-misc

### should-fix — src/main/java/com/intellij/plugins/haxe/util/HaxeModuleDetection.java:8
`import com.intellij.plugins.haxe.v2.toolwindow.HaxeEnvironmentStore;` — a util class (and, in this same chunk, `codeInsight/daemon/HaxeProjectSdkSetupValidator.java:33`) imports a store out of the `v2/toolwindow` UI package. `HaxeEnvironmentStore` is a `PersistentStateComponent` (pure data, no UI) and is also imported by five `v2/buildtools` classes and two `v2/display` classes — exactly the checklist case "a constant or store being the excuse means it lives in the wrong package". Fix: move `HaxeEnvironmentStore` to a non-UI package (`v2/buildtools` or a `v2/settings` home); the toolwindow keeps consuming it in the correct direction. (The store itself is outside this chunk; flagged here because these importers make the direction violation.)

### minor — src/main/java/com/intellij/plugins/haxe/util/HaxeResolveUtil.java:1703
`fullyResolveTypedef` (and its `getTypeParameters` helper) is moved verbatim from develop's `HaxeResolver`, so its defects are legacy — but a relocation is an edit, and the moved block carries: a `@Nullable HaxeGenericSpecialization specialization` parameter dereferenced unconditionally at line 1723 (`specialization.get(type, ...)`) — every current caller passes the `@NotNull` `getSpecialization()`, so the annotation is simply wrong and should be `@NotNull`; a vague `//TODO resolve  with resolver ?` (line 1743) that does not say what is deferred; and visibly broken indentation (lines 1729–1748 drift between 6 and 10 spaces, plus the double blank line at 1720–1721). A quick tidy while the code is being moved anyway would satisfy the "legacy construct in code you are already changing" rule.

### minor — src/main/java/com/intellij/plugins/haxe/util/HaxeResolveUtil.java:1732 (moduleNameOf)
The new helper hand-rolls strip-the-extension: `int dot = fileName.lastIndexOf('.'); return dot < 0 ? fileName : fileName.substring(0, dot);`. The platform's `FileUtil.getNameWithoutExtension(file.getName())` already does this and is used in five places in this codebase (including `HaxeFileModel:162`, which computes exactly this "module name" notion). Use the existing helper — or `haxeClass.getModel()`/`HaxeFileModel` if a model is reachable — instead of a third spelling of the domain fact.

### minor — common/src/main/java/com/intellij/plugins/haxe/config/LimeTarget.java:9
`LimeTarget` (added) and `OpenFLTarget` (rewritten in this branch, whose javadoc says "based on lime targets") now contain the identical 14 entries with identical `HaxeTarget` mappings, and both — plus `NMETarget` — repeat the same fields/constructor/`getTargetFlag`/`getFlags`/`getOutputTarget`/`toString` boilerplate. If OpenFL's target list is by definition Lime's, one enum (or delegation) removes a list that must now be updated twice in lockstep; at minimum the shared accessor surface wants one interface the three enums implement instead of three copies.

### minor — src/main/java/com/intellij/plugins/haxe/util/HaxeQnameResolveUtil.java:62
The `filter` lambda in `resolveRuntimeName` takes three steps (fetch FQN, build `withNoModuleName`, compare against `info.toClassQualifiedName()`) — per the "non-trivial lambdas become named methods" rule it should be a private predicate (e.g. `matchesClassQname(aClass, classQname)` with the loop-invariant `info.toClassQualifiedName()` hoisted above the stream), so the pipeline reads as a sentence.

### minor — src/main/java/com/intellij/plugins/haxe/HaxeFileType.java:62
The branch mixes annotation libraries inside one signature: `getCharset(@NotNull VirtualFile file, byte @NonNull [] content)` uses JetBrains `@NotNull` for the first parameter and jspecify `@NonNull` for the array (same in `HXMLFileType.java:64` and the new `HxpFileType.java:65`). `org.jetbrains.annotations.NotNull` is TYPE_USE-capable, so `byte @NotNull [] content` works and keeps the file on the one annotation library the rest of the codebase uses; drop the `org.jspecify` import.

### minor — src/main/java/com/intellij/plugins/haxe/editor/HaxeRestoreReferencesDialog.java:45
The field block this branch edits still carries the commented-out `//private boolean myContainsClassesOnly = true;` directly beneath the changed `mySelectedElements` declaration. Commented-out code in edited surroundings is on the checklist — delete the line.
