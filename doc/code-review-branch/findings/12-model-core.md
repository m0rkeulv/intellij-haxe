# 12-model-core

### should-fix — src/main/java/com/intellij/plugins/haxe/model/type/HaxeGenericResolver.java:123
Commented-out code left behind by the `addAll` rework: `//this.add(resolver.typeParameter(), resolver.type(), resolver.index());` sits directly above its replacement `this.addInternal(resolver);`. The checklist explicitly flags commented-out code left after a workaround replaced it — delete the line (the comment on line 121 already explains why plain `addAll` is not used).

### should-fix — src/main/java/com/intellij/plugins/haxe/model/type/HaxeGenericResolver.java:152-184
`addInternal`, `addConstraintInternal` and `addArgumentInternal` are three copies of the same body (same repeated header comment three times), differing only in which list they mutate and the removeIf predicate. Worse, each one destructures the entry into five locals only to rebuild an identical `ResolverEntry` — records are immutable, so `new ResolverEntry(name, typeParameter, type, scope, restIndex)` is just a copy of the argument. Each method collapses to two lines: `resolvers.removeIf(entry -> isSameTypeParameter(typeParameter, entry, resolverEntry.index())); resolvers.add(resolverEntry);` — and once they are two-liners the triplication is a single shared helper taking the target list (arguments keeps its own `.equals()` predicate, matching public `addArgument`).

### should-fix — src/main/java/com/intellij/plugins/haxe/model/HaxeMemberModel.java:81-90
`isDeclaredPublic()` repeats the first three terms of the existing `isPublic()` verbatim (`hasModifier(PUBLIC) || ((isInterface()||isExtern()) && !PRIVATE) || hasCompileTimeMeta(PUBLIC_FIELDS)`); only the override term differs (`isOverriddenPublicMethod()` vs `OVERRIDE && !PRIVATE`). Same predicate spelled in 2+ places — extract the shared prefix into one private method (e.g. `declaredOrClassDefaultPublic()`) that both call, each adding its own override term. The module-member `declaringClass == null → !hasModifier(PRIVATE)` branch is also duplicated between the two.

### should-fix — src/main/java/com/intellij/plugins/haxe/model/HaxeMethodModel.java:226
`getReturnTypeCacheProvider` no longer describes what the method does. It was named for its old role as a `CachedValuesManager` provider; after this branch it is a plain compute function returning `ResultHolder`, passed as a `Supplier` to `HaxeExpressionEvaluatorCacheService.methodReturnType`. Names must describe current behaviour — and since the body is one line (`HaxeTypeResolver.getFieldOrMethodReturnType(haxeMethod, null)`), the cleanest fix is inlining it into the lambda at line 197 (the wrapper only forwards, adding nothing beyond binding `null`); otherwise rename to `computeReturnType`.

### question — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:253-267, src/main/java/com/intellij/plugins/haxe/model/HaxeStdPackageModel.java:110-120
Both new `CachedValue`s (`modulesMainClassResult`, `stdRootTypesResult`) pass the `PsiDirectory` itself plus every child `PsiFile` as dependencies, and their javadocs claim "Rebuilds only when a file in the directory changes." A `PsiDirectory` dependency has no containing file, so the platform's timestamp for it degrades to the global PSI modification count — meaning the cache actually invalidates on ANY PSI change anywhere (and the per-file dependencies are then redundant); if it did not degrade that way, a newly ADDED file would not invalidate the value at all, which would be a stale-cache bug for `getModulesMainClass`. Verify which behaviour holds; either depend explicitly on `PsiModificationTracker.MODIFICATION_COUNT` (dropping the per-file deps) or find a tracker with the claimed granularity — and align the javadoc with whichever invalidation is real.

### question — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:153-168
`findFileByIndex` treats an empty `FilenameIndex` result as a definitive miss (`if (candidates.isEmpty()) return null;`), and the directory-walk fallback runs only on `IndexNotReadyException` (dumb mode). Any root reachable through `HaxeSourceRootModel` whose files are NOT covered by `GlobalSearchScope.allScope(project)`'s file-name index (e.g. a root present via `OrderEnumerator` classes roots but excluded from indexing) would silently stop resolving, where the old walk found the file. If every model root is guaranteed indexed under allScope this is fine — confirm, and if there is a known exception, fall back to `findFileByDirectoryWalk` on an empty candidate set for that case.

### minor — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:184
`getRelative` is annotated `@org.jspecify.annotations.NonNull` while the rest of the file (and the codebase) uses `org.jetbrains.annotations.NotNull` — an accidental wrong auto-import (line 36). Switch to `@NotNull` and drop the jspecify import.

### minor — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:198-216
`findFileByDirectoryWalk` spells the same lookup block twice — `root.access(accessPath)` / `directory.findFile(fname + ".hx")` / `file != null && file.isValid() && file instanceof HaxeFile` — once for the primary root and once inside the all-roots loop. Extract one helper (`findHaxeFileInDirectory(rootModel, accessPath, fname)`) and call it from both places, mirroring how the index path already shares `findInRoot`.

### minor — src/main/java/com/intellij/plugins/haxe/model/HaxeProjectModel.java:154
The new `stdInScope` line re-spells the sdk-scope predicate that line 139 already evaluates (`sdkRoot.root != null && searchScope.contains(sdkRoot.root)`, with inverted null-handling). Same expression evaluated in several branches becomes one local: compute `boolean sdkInScope = sdkRoot.root != null && searchScope != null && searchScope.contains(sdkRoot.root);` once above the sdk block and derive both conditions from it.

### minor — src/main/java/com/intellij/plugins/haxe/model/type/HaxeGenericResolver.java:112-114, 150-151, 185-188
Stray blank-line runs added between methods: a double blank after `addArgument` (112-114), and a triple blank between `addArgumentInternal` and `resolveArgument` (185-188), plus new lone extra blanks after `add`/`addArguments`. Blank lines separate groups — one per boundary; collapse the runs.

### minor — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:33-36 (and 4 sibling files)
New imports were appended after the `java.util` block / after `import static` lines instead of being merged into the existing import group — same pattern in HaxeImportableModel.java:31, HaxeGenericResolverUtil.java:36, HaxeTypeResolver.java:51, SpecificHaxeClassReference.java:46, HaxeGenericResolverCastUtil.java:18. Cosmetic, but it reads as leftover auto-import churn; fold them into the sorted import block.

### minor — src/main/java/com/intellij/plugins/haxe/model/HaxePackageModel.java:167
Trailing-whitespace-only line inside `findFileByIndex` (the blank line before `return null;` carries four spaces). Strip it.
