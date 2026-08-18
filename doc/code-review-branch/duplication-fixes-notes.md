# Duplication fixes — decisions taken where the finding left room

- **Row 24 (`HaxeCompileCommands` per-type dispatch):** the strategy lives in
  `v2/buildtools/HaxeBuildFileActions`, not on `HaxeBuildFileType` — methods on
  the enum would import buildtools from `v2/toolwindow/tree` and cycle the two
  packages while the enum awaits its planned move (row 14); a TODO on the class
  records folding it into the enum after that move. The hxp-script facts
  (`HXP_SCRIPT_BUILD_ACTION`, `hxpScriptCommand`) moved out of
  `HaxeCompileCommands` into a new `HxpScriptProjects`, so all four build-file
  families have a facts class (`HxmlProjects`/`LimeProjects`/`NmeProjects`/
  `HxpScriptProjects`) and the dispatch class references no caller.
- **Row 26 (background-eval machinery):** extracted as package-private
  `v2/buildtools/HaxeProjectInfoCache<V>` (generic value, run function passed
  per ask, optional on-evict hook — NME passes `deletePreparedDir`). Ordering
  semantics kept verbatim, including the drain-callbacks-before-dropping-in-flight
  comment (now only in the helper). Lime's `authoritative` and NME's
  `evaluation != null` freshness terms unified as `Outcome.settled`. The
  chunk-05 minor (`firstErrorLine(output)` spelled 3×) folded into the helper
  as suggested there.
- **Row 27 (hxml knowledge):** line parsing and one-level reference expansion
  landed in a new sibling `v2/buildsystem/HxmlArguments` rather than inside
  `HxmlFileParser` — the parser stays IO-free (includes go through its
  `IncludeResolver`), while expansion reads files and logs.
  `HxmlFileParser.DEFINE_FLAGS` made public and reused by
  `HaxeDisplayConfiguration.withoutRemovedDefines`. The two expansion tests
  moved from `HaxeGeneratedDumpServiceArgsTest` to a new `HxmlArgumentsTest`;
  the dump-specific `--next` TODO moved onto `HaxeGeneratedDumpService.dumpArgs`.
