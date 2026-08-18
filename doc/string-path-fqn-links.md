# Navigable file paths and FQNs in string literals

Research + implementation plan: make string literals whose content is a
resolvable file path or fully-qualified name ctrl-clickable, like the
platform's URL links in strings — but navigating inside the IDE.

## How the platform does it (research findings, 2026.2)

- **URLs in strings** are ordinary `PsiReference`s contributed to string
  literals (`WebReference`, `intellij.platform.lang.impl`); ctrl-click and
  ctrl-hover-underline are what ANY resolvable reference gets from
  Go-to-Declaration — no special URI plumbing involved.
- **The persistent link styling** comes from the marker interface
  `com.intellij.codeInsight.highlighting.HighlightedReference` (public,
  non-internal), whose javadoc names our exact use case: *"non-soft
  references in non-obvious places like String literals which have some
  navigation target"*. Implementing it applies the
  `HIGHLIGHTED_REFERENCE` text attributes via a platform pass — no custom
  annotator needed. (A newer `@ApiStatus.Experimental` Symbol-based variant
  `PsiHighlightedReference` exists; not used — experimental.)
- **`psi_element://`** (built by `DocumentationManagerUtil.createHyperlink`,
  which our doc renderer already uses in
  `HaxeDocumentationRenderer`/`HaxeDocumentationCodeVisitor`) is the link
  format for RENDERED HTML — doc popups and comment rendering. Editor
  string links do not need it: a `PsiReference` navigates natively. The two
  mechanisms meet only in that both end at the same PSI element.
- **File paths in strings** have dedicated platform machinery,
  `FileReferenceSet` (per-segment references, path completion, rename
  refactoring participation). Powerful but configuration-heavy for the
  multi-base resolution we want; the plan starts with a single
  whole-literal reference and names FileReferenceSet as the upgrade path.

Reusable pieces already in the plugin:

- `HaxeReferenceUtil.textCanBeQname` — the FQN shape check.
- `FqnNavigateLink.resolve` (debugger value links) — FQN → PSI element,
  including the runtime-name fallback (module names absent at runtime).
  To be EXTRACTED into a shared util; the string reference and the debugger
  link must not own two copies.
- No `psi.referenceContributor` is registered yet for Haxe — this is a new
  registration, colliding with nothing.

## Design

### Reference contribution

`HaxeStringLiteralReferenceContributor` (`PsiReferenceContributor`,
registered `<psi.referenceContributor language="Haxe" .../>`) contributes to
`HaxeStringLiteralExpression` only when ALL cheap gates pass:

- constant string: no interpolation entries (`shortTemplateEntry`/
  `longTemplateEntry` children) — interpolated content has no stable value;
- length cap (~260 chars) and a shape pre-filter: path candidates need a
  separator or an extension-ish tail, FQN candidates must pass
  `textCanBeQname` — so the expensive step never runs on ordinary prose.

Two reference classes, both implementing `HighlightedReference`:

- **`HaxeStringFilePathReference`** — resolves the literal against three
  bases, first hit wins:
  1. absolute path (only attempted when the text LOOKS absolute: drive
     letter or leading `/`),
  2. relative to the project base dir,
  3. relative to the directory of the file containing the string.
  Separators normalized (`\` vs `/`); resolution lands on the `PsiFile`.
- **`HaxeStringQnameReference`** — resolves via the shared FQN util
  (extracted from `FqnNavigateLink`): class/member by qualified name with
  the runtime-name fallback. Resolution lands on the class or member PSI.

### "Only show when resolvable"

The requirement has two layers and both are covered:

- Ctrl-hover underline + ctrl-click only appear when the reference
  RESOLVES — platform behavior, nothing to do.
- The persistent `HIGHLIGHTED_REFERENCE` styling must also be gated on
  resolvability. `HighlightedReference` is documented for non-soft
  references; the implementation choice (verify during build): either
  attach references only after a resolvability check in the contributor, or
  attach soft references and rely on the highlighting pass skipping
  unresolved ones (`isHighlightedWhenSoft` default false). Whichever holds
  up in a fixture test wins; resolution results are cached by
  `ResolveCache` either way, and the pre-filters prune most strings before
  any resolution happens.

### Steps

1. Extract the FQN resolution from `FqnNavigateLink` into a shared util
   (debugger link keeps calling it).
2. Path resolver util: three-base lookup, separator normalization,
   absolute-shape detection.
3. The contributor + two reference classes + plugin.xml registration.
4. Fixture tests: resolvable absolute / project-relative /
   file-relative path, FQN class, FQN member (dot path), unresolvable
   string contributes NO navigation, interpolated string skipped,
   highlighting present exactly on the resolvable ones.
5. Optional polish, own decision points: `bindToElement`/rename support
   (or the `FileReferenceSet` upgrade) so renaming a file updates path
   strings; skipping generated-preview files.

## Completion inside the strings (phase 2 — BUILT)

Both halves are built.

File paths: `HaxeStringFileReferenceSet` (per-segment references,
containing-dir + project-root contexts), attached to every clean constant
string so explicit completion works from the first character; painting is
gated separately on shape + last-segment resolution. Fixture tests
confirmed the platform's reference-variant completion surfaces
`FileReference` variants with NO custom contributor.

Qualified names: `HaxeStringQnameCompletionContributor` suggests
segment-wise from the FQN unified index's key set (stub + file-based +
compiler catalog via the new `getAllKeys`/`allFqns`), plus members once the
prefix resolves to a class.

Auto-popup policy, shared between the confidence and the typed handler:
NOTHING pops on the first word; paths pop from the first `/`; qualified
names pop only from the SECOND dot on, and only when the prefix before it
is a known package/class (`HaxeQnameResolveUtil.isKnownQnamePrefix`) — a
single dot is everyday prose. Explicit ctrl-space works everywhere from the
first character.

Typing `"assets/"` should offer that directory's children; typing `"com."`
should offer packages/types/members. Feasibility differs sharply by half:

**File paths — mostly free.** `FileReferenceSet` (the platform's file-path
machinery) produces per-segment references whose `getVariants()` IS the
completion: after `assets/` the last segment's variants are the resolved
directory's children. Migrating the file side from the single whole-literal
reference to a `FileReferenceSet` subclass (custom default contexts:
project root + containing dir; absolute paths native) buys completion,
per-segment navigation AND rename-refactoring updating path strings.
Design shift required: completion must work MID-TYPING, when the full path
does not yet resolve — so the file side attaches its reference set on path
SHAPE, while the link painting stays gated on the final segment resolving
(the color annotator asks the last reference). Two small standard pieces
for auto-popup, since the platform suppresses completion auto-popup inside
string literals: a `CompletionConfidence` opting our strings back in, and a
`TypedHandlerDelegate` scheduling the popup on `/`. Explicit ctrl-space
works without either.

**FQNs — custom but straightforward.** No free machinery; a
`CompletionContributor` positioned inside string literals, active once the
content before the caret is qname-shaped (`word.` at minimum):

- packages + types: prefix-filter over the FQN class/module unified
  indexes' key sets — the `getAllKeys` stub+file merge pattern already
  exists on sibling unified indexes and the FQN ones gain it trivially;
  next-segment package names are derived from the keys, classes come from
  keys in the typed package.
- members after `pack.Class.`: resolve the class
  (`findClassByQName`, already in use) and list its model's members.
- bonus: the compiler type catalog can contribute generated types the
  source indexes cannot see, consistent with the rest of v2.
- insertion: `CompletionResultSet.withPrefixMatcher(segment after the last
  dot)` — standard.

Estimate: one session per half. Risks: `FileReferenceSet` context
configuration (Windows separators, absolute paths) is the fiddly part of
the file half; auto-popup UX tuning; completion-in-string fixture coverage.

### Risks / notes

- Perf: references are (re)computed by highlighting passes; the gates keep
  the hot path to a length check + char scan. FQN index lookups are cheap
  and cached.
- Windows: case-insensitive paths, backslashes in string literals arrive
  ESCAPED in source (`"foo\\bar.hx"`) — the reference must read the
  literal's VALUE, not its raw text.
- False positives ("haxe.ui" prose that happens to resolve) are acceptable
  by design — the feature only decorates, never errors.
