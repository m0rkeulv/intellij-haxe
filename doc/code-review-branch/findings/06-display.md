# 06-display

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeGeneratedDumpService.java:328
`clearCaches()` has zero callers anywhere in the codebase (checked `\.clearCaches\(\)` across src/main/java). Per the checklist, consumer-less API is deleted or carries a TODO naming the wiring it waits for. The natural wiring already exists: `HaxeCompilerCaches.clearAndRehighlight` documents itself as "The one entry point for dropping everything derived from the compilation server ... Callers never clear individual services" yet does not clear the dump service. Either add `HaxeGeneratedDumpService.getInstance(project).clearCaches();` there (a purged/restarted world should not serve a stale `DumpState`) or delete the method.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerDisplayService.java:31-35
Package direction: the display services import `v2.toolwindow` classes — `HaxeActiveBuildFileStore`, `HaxeEnvironmentStore`, and `toolwindow.tree.HaxeBuildFile`/`HaxeBuildFileScanner`/`HaxeBuildFileType` here, plus `HaxeEnvironmentStore` again in `HaxeDisplayConfiguration.java:4`. The checklist says non-UI code never imports UI/toolwindow classes and "a constant or store being the excuse means it lives in the wrong package". The stores and the build-file scanner/type are model-level (persisted state, file classification), consumed by services, indexes and annotators — they belong outside `toolwindow` (e.g. `v2/buildtools` or a `v2/model` package), with the tool window as one consumer.

### should-fix — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeDisplayConfiguration.java:71,82
hxml domain knowledge is spread across callers instead of living in its domain class. Three symptoms: (1) `withoutRemovedDefines` re-spells the define-flag predicate `arg.equals("-D") || arg.equals("--define")` that `v2/buildsystem/HxmlFileParser` already owns as `DEFINE_FLAGS`; (2) hxml line semantics ("one flag per line, everything after the first space is the single argument, `#` comments") are implemented in `HaxeCompilerDisplayService.parseHxmlLines` while `HxmlFileParser.parseInto` implements the same conventions a second time; (3) one-level hxml expansion lives in `HaxeGeneratedDumpService.expandHxmlReferences` but is consumed from `HaxeDisplayConfiguration.applyOverrides` — a define-override concern reaching into the dump service for generic hxml handling. Move line parsing and reference expansion into the hxml domain (`HxmlFileParser` or a sibling) and reuse `DEFINE_FLAGS`.

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerMetadataService.java:38
The `Registry` record's `port` component is written (`new Registry(connected.port(), ...)`) but never read — the map `registries` is already keyed by port, and lookups only touch `entries()`/`bareNames()`. Dead data; drop the component.

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerResolveService.java:134
`// check if we got a real PSI element before we use compiler blueprint` narrates the next line and uses "we", both of which the comment rules forbid ("Comments state behaviour, not history or authorship"; no narration of what the next line does). The code (`getMember(name, null) != null → return null`) already says it; delete the comment or state the constraint ("members with a real declaration are the static resolver's job"), matching the phrasing already used in `HaxeBlueprintCompletionContributor.addGeneratedMember`.

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerResolveService.java:256-262
`buildBlueprintFile` renders the fixed header via six chained constant `append`s — a multi-line string expression, which the style rules want as a text block. A `"""..."""` block with `.formatted(key.dotPath(), typeName)` shows the shape of the generated extern header at a glance; the member loop can keep appending after it.

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerResolveService.java:213-216
The expression `file.getOriginalFile().getVirtualFile()` (physical file of a PSI element) is spelled in five places in this chunk: private `fileOf` here, a duplicate private `fileOf` in `HaxeBlueprintCompletionContributor.java:89-92`, and inline in `HaxeCompilerUsageService.java:88`, `HaxeUsageSearch.java:84` and `HaxeGeneratedPreviewGotoHandler.java:51`. The checklist gives the same expression in 2+ places one named home — one package-visible helper (or an existing PSI util) replaces all five.

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeCompilerProblemMarker.java:54
`Map<String, VirtualFile> resolved = new ConcurrentHashMap<>();` is a purely local map filled and read on the single calling thread — a plain `HashMap` states that; `ConcurrentHashMap` implies cross-thread sharing that does not exist (the field `markedPaths` above it is the one that genuinely needs the concurrent variant).

### minor — src/main/java/com/intellij/plugins/haxe/v2/display/HaxeGeneratedCodePreview.java:63
`previews` is a static, never-cleared cache holding rendered dump text (`LightVirtualFile`s of library-sized modules) keyed by dump-file path. Entries for a live path are replaced on stamp change, but paths from abandoned contexts (the per-context hash dirs under `haxe-dump/`) accumulate for the IDE process lifetime, and `HaxeCompilerCaches.clearAndRehighlight` / Purge Caches cannot reach it. Either bound it, or make the class a project service with a `clearCaches()` wired into `HaxeCompilerCaches`.
