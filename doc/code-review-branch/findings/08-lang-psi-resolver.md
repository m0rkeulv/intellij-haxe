# 08-lang-psi-resolver

### should-fix — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeIsReferenceToUtil.java:32
`isLocalScopedTarget` tests `declaration instanceof HaxeEnumExtractedValue`, but that branch can
never match: per the grammar (`haxe.bnf` 980/988, `enumExtractedValue ::= enumExtractedValueReference | ...`,
`enumExtractedValueReference ::= componentName`) a component name's parent is
`HaxeEnumExtractedValueReference`, never `HaxeEnumExtractedValue` (the generated reference interface
does not extend it). So extracted-value targets silently never take the fast path — the branch is
dead code and the intended optimization does not fire for them. Fix: test for
`HaxeEnumExtractedValueReference` (and verify with a rename-in-extractor case), or drop the kind
from the list.

### should-fix — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolver.java:27-75
The extraction of the check methods into `HaxeResolveChecks` left roughly 30 dead imports behind in
the now-322-line resolver, e.g. `HaxeFakeComponentBindMethod`, `HaxeMetadataUtils`,
`HaxeCallExpressionEvaluatorCacheService`, `HaxeAbstractForwardUtil`, `ArrayListSet`,
`ConcurrentHashMap`, `org.jspecify.annotations.NonNull`, and the static imports `FAKE_PSI_KEY`,
`NULL_SAFETY`, `findObjectLiteralType`, `getArrayAccessTypeFromClass`, `isInsidePatternMatcher`,
`isPatternMatcher`, `createContextForConstructorCall`, `createContextForMethodCall`,
`translateHaxeStringToJavaString`, `elide`. This is the "commented-out code / leftovers after a
move" class of dead code — run optimize-imports on the file.

### should-fix — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolveChecks.java:118-124
The new `normalizeClassResult` javadoc (line 2331) says "the import/same-package walks return raw
class declarations" and exists to stop the result shape depending on which branch answers first —
but only the import-list path is normalized (`return normalizeClassResults(matchesInImport)`, line
116). The same-package path right below still returns the raw PSI: `HaxeResolveUtil.searchInSamePackage`
returns `model.getBasePsi()` for a main-class match (HaxeResolveUtil.java:1336) and `checkImports`
returns it via `asList(target)` unnormalized. The exact idempotence mismatch the helper was added
for can still occur through this branch; wrap it (`asList(normalizeClassResult(target))`).

### question — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeIsReferenceToUtil.java:53-59
The fast-path check order diverges from `doResolveInner`: here `checkCaptureVar` runs immediately
after `checkByTreeWalk`, but in the full pipeline (HaxeResolver.java:282-295)
`searchInSameFile`, `checkIsModuleName`, `checkIsClassName` and `checkSwitchOnEnum` all run before
`checkCaptureVar`. In Haxe a bare lowercase identifier in a `case` that matches an enum value is
the enum member, not a capture — the full pipeline gets that via `checkSwitchOnEnum`, while the
fast path can answer the same occurrence as the capture var and return `true` for
`isReferenceTo(captureVar)`. The `inSwitchCasePatternPosition` guard only diverts occurrences whose
parent is a `HaxeEnumValueReference`, which a bare case identifier is not. If this divergence was
analyzed and is safe, fine — otherwise divert bare switch-case-pattern occurrences to the full
pipeline too (or reorder to match).

### minor — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolveChecks.java:1248
Comment corrupted by the scripted move: develop said "some typedefs can omit this." and the
`this.` → `HaxeResolveChecks.` rewrite reached into the comment, producing "some typedefs can omit
HaxeResolveChecks." which is nonsense. Restore the original wording ("can omit the import"/"can
omit this").

### minor — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolveChecks.java:54-55
New class javadoc has typos that garble the sentence: "used by HaxeResolver to try to find a
references" and "to try to ma HaxeResolver class simpler" ("ma" → "make", "a references" →
"references").

### minor — src/main/java/com/intellij/plugins/haxe/lang/psi/HaxeResolveChecks.java:615
`if(psiElement instanceof  HaxeEnumArgumentExtractor extractor) {}` — an empty statement with an
unused pattern variable (plus the stray `//` at line 620), carried over verbatim into this new
file. Dead code; delete it.

### minor — src/main/java/com/intellij/plugins/haxe/lang/psi/impl/HaxeReferenceImpl.java:881-943
The same three-line guard — comment "SpecificFunctionReference can return null asResolveResult",
`HaxeResolveResult asResult = holder.getType().asResolveResult();`, `if (asResult != null) return asResult;`
— is pasted three times in `resolveHaxeClassInternal`. Per "the same expression spelled in 2+
places gets one named home", extract a small helper (e.g.
`@Nullable HaxeResolveResult asResolveResultOrNull(ResultHolder holder)`) carrying the comment
once, and call it from all three sites.
