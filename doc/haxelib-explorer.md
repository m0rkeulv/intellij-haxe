# Haxelib Explorer

Research + design: a permanent Explorer tab in the existing "Haxelib" tool
window (today: install-console tabs only, empty otherwise) for browsing
libraries — installed state, versions, docs — with install/remove actions.
Inspiration: the Git Log view (filterable master list + details pane).

## Data sources (all live-verified 2026-08-16)

### The haxelib CLI is the only API

- **`haxelib list`** — LOCAL and fast: every installed library with all
  installed versions; the selected one in brackets. Markers verified:
  `box2d: 1.2.3 [1.2.4]` (two versions, 1.2.4 current), `castle: [git]`,
  `hxcpp-debug-server: 1.2.4 [dev:c:\...path]`.
- **`haxelib info <name>`** — SERVER query, one call per library, rich:
  name, description, website, license, owner, tags, current version, and
  the FULL release history with dates and per-release notes
  (`2013-12-31 22:17:57 0.9.2 : Restore Emscripten support`). This is the
  version-table and not-installed-description source. Cacheable per session.
- **`haxelib search <word>`** — SERVER query, names only. And the FULL
  catalog is one call away: `haxelib search " "` (a lone space — empty
  would be dropped from argv) matches everything, 2017 libraries verified.
  The existing `HaxelibCacheManager.readAvailableOnline` already uses
  exactly this trick.
- **No JSON/REST API exists.** `lib.haxe.org/api/3.0/` answers only the
  haxe-remoting serialization protocol (probed: "Invalid remoting call") and
  there is no list-all or versions.json endpoint (probed 404). The CLI IS
  the sanctioned client; shelling out to it is the honest channel, and it
  also honors the user's proxy/config.

### Local repository files (offline, installed versions only)

`<repo>/<lib>/<version-with-commas>/` holds the release's full content:
`haxelib.json` (description, license, tags, contributors, dependencies,
releasenote) plus whatever the release shipped — lime has `README.md`,
`CHANGELOG.md`, `LICENSE.md`. `<repo>/<lib>/.current` holds the selected
version; `.dev` the dev path. Directory presence == installed, no CLI call
needed for the installed set's details.

Repo scoping is a REQUIREMENT: the project's haxelib repository is the
source of truth for what is installed — when a local `.haxelib` exists,
that repo, not the global one. haxelib resolves it from the working
directory, so every CLI call runs with the project/module dir as cwd; the
existing `HaxelibInstalledIndex.fetchFromHaxelib(sdk, moduleDir)` already
follows this pattern and stays the installed-index source.

### The website, for what the CLI cannot give

Per-VERSION deep links verified: `https://lib.haxe.org/p/<name>/<ver>/`
plus `/releasenotes`, `/changelog`, `/license` (200), and
`/p/<name>/versions/`. Scraping their HTML is fragile and adds nothing the
CLI lacks except the README of a NOT-installed library — for that, an
"Open on lib.haxe.org" action on the exact deep link beats re-rendering.

## Layout: two panes, not three (recommendation)

The question was whether the middle "versions" pane earns its place. It
does not, for the same reason the Git Log has no middle pane:

- A bare version list wastes a full pane's width on short strings, and a
  second selection model (library AND version independently) complicates
  every action's context.
- Versions carry MORE than a list can show: date, installed marker,
  current marker, release note — that is a TABLE, and it fits naturally at
  the top of the details pane (exactly where the Git Log shows the commit's
  files).
- A tree (versions nested under libraries in the left list) double-scrolls
  and makes search/filter results noisy — rejected for the master list.

So: `OnePixelSplitter`, left ~40%:

- **Left — library list** (`JBList` + speed search): one row per library,
  name + one-line description (grayed) + state icons (installed / dev / git
  / update available). Toolbar above: `SearchTextField` + a filter popup
  action (checkbox group: Installed, Not installed, Dev, Git, Update
  available) + refresh — the Git Log toolbar shape.
- **Right — details** for the selected library, vertically split:
  - **Versions table** (`TableView`): version, date, note, markers for
    installed/current. Rows drive version-scoped actions and doc scoping.
  - **Docs tabs** (`JBTabbedPane`): README | CHANGELOG | Release notes |
    LICENSE | Info. Tabs appear only when their source exists.

## Docs rendering

- **Markdown → HTML with the library the plugin already ships**: the
  HaxeDocs renderer (`HaxeDocumentationRenderer`) converts markdown through
  **commonmark** (declared dependency, plus its autolink and gfm-tables
  extensions) — the explorer reuses that same stack, extracting the
  parser/renderer setup into a shared home so docs popups and the explorer
  render identically (one styling, one extension set). Display in a
  `JEditorPane` built by `HTMLEditorKitBuilder` (public platform API) —
  theme-aware. (The platform also bundles `org.intellij.markdown`, but a
  second markdown stack next to the existing commonmark one would be
  duplication for nothing.)
- **Installed library**: README/CHANGELOG/LICENSE tabs read the selected
  VERSION's local files; Release notes tab renders the `haxelib info`
  release list; Info tab renders haxelib.json fields (description, license,
  tags, dependencies, website as a link).
- **Not installed library**: Info + Release notes from `haxelib info`;
  README/CHANGELOG/LICENSE tabs replaced by an "Open on lib.haxe.org" link
  to the version deep links. (A JCEF embed of the site page is possible
  later; not worth the weight and the offline hole now.)

## List model, filters and the cache

Two layers, both already half-built in `HaxelibCacheManager` (per-module
instances; improved rather than replaced — see Structure):

- **Installed index** (local, instant): the project-repo-scoped
  `HaxelibInstalledIndex` — names, versions, current/dev/git states.
- **Full catalog** (server, one call): the match-all search — 2017 names.
  Cached for the session; names only until a library is selected, when a
  cached `haxelib info` fills description, versions and dates lazily.

The list shows the union with the filter popup deciding visibility —
default: Installed checked, so opening the tab is instant and offline; the
"Not installed" filter reveals the whole cached catalog, and the search
field narrows either live (local filtering — no per-keystroke server
calls). "Update available" derives from comparing installed-current
against the cached info's latest, computed lazily for visible rows only.

Caching policy per the existing manager's shape: everything server-fetched
(catalog, per-library info) is CACHED and reused across the session; a
toolbar **Refresh** (and a context "Refresh library info") is the explicit
force-update that drops and refetches — no time-based invalidation
guessing. Installed state refreshes cheaply on tool-window activation and
after every mutation.

## Actions (context menu; version-scoped ones on table rows too)

- Install latest / Install this version — through the existing
  `HaxelibInstaller` (keeps the selected-version pinning semantics) with
  the output in a console tab beside the Explorer, exactly like today's
  install consoles.
- Remove version / Remove library (`haxelib remove`), with a confirmation
  naming what goes.
- Set current (`haxelib set <name> <ver>`).
- Update (`haxelib update <name>`).
- Open on lib.haxe.org; Show local files (installed versions).
- After any mutation: refresh the model and kick the existing v2 library
  sync so External Libraries and resolve follow immediately.
- Future tie-in (not this arc): "Add -lib to active build file".

## Tool window integration

- `HaxelibConsoleWindowFactory.createToolWindowContent` adds the Explorer
  as content with `setCloseable(false)`; drop `setToHideOnEmptyContent`
  (the window is never empty now). Install-console tabs keep appending
  after it unchanged.
- The model loads lazily on first tool-window activation, in the
  background; the list shows a loading state meanwhile. All CLI calls off
  the EDT; `haxelib info` results cached per session with the refresh
  action as the invalidation.

## Structure

Build ON the existing haxelib layer instead of a parallel one:

- **`HaxelibCacheManager`** (exists, per-module: installed + available maps,
  `reload()`, `fetchAvailableVersions` already parsing `haxelib info`) —
  improved to carry the explorer's needs: the full info parse (description,
  license, owner, release dates + notes — its version-line regex already
  matches the format), an explicit force-refresh API, and a review of its
  static instance map (thread-safety, module disposal) while touching it.
  `HaxelibInstalledIndex` + `HaxelibCommandUtils` stay the CLI seam.
- `HaxelibExplorerPanel` + list/table/docs subcomponents (toolwindow side,
  new; never imported by the model layer).
- Markdown rendering extracted from `HaxeDocumentationRenderer`'s
  commonmark setup into a shared converter both call.
- `HaxelibExplorerActions` — the context/toolbar actions over the manager
  plus `HaxelibInstaller`.
- Bundle keys in `HaxeBundle` (`haxelib.explorer.*`).

(#31 note: this graduates `HaxelibCacheManager` and friends into the KEPT
set — the V1-removal task's five consumers are unaffected.)

## Phases

1. `HaxelibCacheManager` improvements (full info parse, force-refresh,
   instance hygiene) with parsing unit tests; Explorer tab with the
   installed+catalog list, filters, search and the Info details pane.
2. Versions table + install/remove/set/update actions with console output
   and post-mutation sync (reusing `HaxelibInstaller` and the v2 library
   sync).
3. README/CHANGELOG/LICENSE markdown tabs (shared commonmark converter),
   website deep links, update-available badges.

## Open decisions

- Whether `haxelib info` parsing should tolerate the CLI's "new version
  available" preamble noise (it must — verified present in output).
- Dev/git rows: the dev PATH is shown in Info; whether "Remove" should
  offer `haxelib dev <name>` -off for dev states.
- Multi-module projects with different local repos: phase 1 uses the
  project base dir's repo; per-container repos only if a real project
  needs them.
