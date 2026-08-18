# Minor-fixes notes

Decisions taken while fixing triaged minor findings (see minor-triage.md;
findings/10-annotators.md, 16-ide-misc.md, 17-v2-compiler-runconfig-buildsystem.md).

## 1. HaxeLanguageLevel.previous() + dead-API deletes (chunks 10, 17)

- Added `HaxeLanguageLevel.previous()`: returns the level immediately below,
  and at the floor (the oldest constant) returns ITSELF rather than throwing.
  Clamping was chosen over `null`/exception so callers always hold a usable
  level — the one caller builds a "lower the language level" quickfix, and a
  floor `removedIn` (impossible today, the earliest removal is 4.0) would
  degrade to a no-op fix instead of an AIOOBE.
- `HaxeStandardAnnotation.removedAtLanguageLevel` now calls
  `removedIn.previous()` instead of `values()[ordinal - 1]`.
- Deleted `HaxeLanguageLevel.getMajor()` — zero callers (the `getMajor()`
  hits elsewhere are on `HaxelibSemVer`). The `major` field stays: it backs
  `getVersionString()` and `fromVersionString()`.
- Deleted the `HaxeCompilerSettings.getEffectiveLanguageLevel(Module)`
  default overload — zero callers; every caller resolves the module name
  itself and uses the String form. The `Module` import stays for the
  class-javadoc `{@link Module}`.

## 2. isConsideredUsed on HaxeUsageSearch (chunk 16)

- Added `HaxeUsageSearch.isConsideredUsed(declaration)` and the
  `(declaration, scope)` overload. The subtle tri-state comment (USED covers
  compiler-known/generated usages; UNKNOWN keeps the static verdict until
  the compiler answer lands) now lives once on the helper's javadoc.
- All four unused-* inspections (`HaxeUnusedFieldInspection`,
  `HaxeUnusedFunctionInspection`, `HaxeUnusedLocalVarInspection`,
  `HaxeUnusedMethodInspection`) call it; the duplicated two-line comment is
  gone from each. `usageState` remains public — the tri-state answer is
  still the right API for callers that need to distinguish UNUSED from
  UNKNOWN.

## 3. AnnotatorUtil.shouldSkip bulk conversion (chunk 10)

- Added `AnnotatorUtil.shouldSkip(element)` combining the invalid-element
  and generated-preview guards. Order deliberately flipped versus the old
  inline pair: `isValid()` first, because `isInGeneratedPreview` asks the
  element for its containing file, which an invalidated element cannot
  answer.
- Scripted perl pass converted the guard pair at 22 sites (21 in
  ide/annotator/semantics + HaxeUnresolvedTypeAnnotator), explicitly
  excluding AnnotatorUtil.java per the bulk-edit rule. Each file converted
  exactly once; no `isInGeneratedPreview(element)` guard remains outside
  AnnotatorUtil.
- The three color annotators (HaxeFastColorAnnotator,
  HaxeInvalidEscapeAnnotator, HaxeSlowColorAnnotator) keep their bare
  `isValid()` guards on purpose: color annotators must keep highlighting
  the generated-code preview (see AnnotatorUtil.isInGeneratedPreview's
  javadoc), so they must NOT adopt shouldSkip.

## 4. HxpEvaluator stdout/stderr deadlock (chunk 22)

- `evaluateIn` drained stdout to EOF before touching stderr: a child that
  fills the stderr pipe buffer blocks writing while we block reading
  stdout — both processes deadlock. A `sys.thread.Thread` now drains stderr
  concurrently and hands the text back via the thread message queue; the
  blocking `Thread.readMessage(true)` doubles as the join before
  `exitCode()`. Chosen over a select/poll loop because `sys.thread` works
  identically on both build shapes of the tool (`--jvm` ship jar,
  `--interp` test runs).

## 5. Renderer + wizard input guards (chunks 01, 14)

- `HaxeToolWindowTreeRenderer.isBuildAction`: the accidental case-sensitive
  `equals("compile")` is now `equalsIgnoreCase` like its two neighbours.
- `HxmlTemplateStep.setupProject` (HaxeTemplateStep.kt): the Flash stage
  fields no longer concatenate raw text into `--swf-header`. Width/height/fps
  use `toIntOrNull ?:` with the field defaults (960/640/60), matching
  LimeFamilyTemplateStep's guard; the color must be an RGB hex sextet or
  falls back to `ffffff`; `--swf-version` is emitted only when the field is
  a plain number (`14` or `11.2`), otherwise the line is dropped (its field
  default is empty, so "drop" IS the default, not a silent rewrite).

## 6. Dead-API sweep leftovers (chunks 01, 09, 21)

- `HAXE_LOGO_GRAY_13`: kept the field and wired the three plugin.xml
  registrations to it (`icon="icons.HaxeIcons.HAXE_LOGO_GRAY_13"`). All
  three are `<toolWindow>` registrations, and `ToolWindowEP.icon` documents
  the qualified-field-name form, so the constant becomes the single home
  for the path and gains compile-adjacent checking.
- `HaxeToolWindowNavigation.fileDescriptor(project, file, offset)`: all four
  callers passed 0 — parameter dropped, now uses the offset-less
  `OpenFileDescriptor(project, file)` constructor.
- `HaxeFqnIndexUtil.resolveModule`: the trailing `FullyQualifiedInfo fqn`
  parameter was never read — dropped, three index callers updated
  (the module-name index's now-unused `fqn` local removed with it).
