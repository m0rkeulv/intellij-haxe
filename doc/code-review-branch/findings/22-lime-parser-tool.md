# 22-lime-parser-tool

### should-fix — tools/LimeProjectParser/build.gradle.kts:39-41
`buildParser` declares `inputs.dir("src/main/haxe")` and `inputs.file("build.hxml")` but not `src/main/resources`, even though `build.hxml` embeds `src/main/resources/HxpRunner.hx` into the jar via `-resource`. Editing HxpRunner.hx therefore leaves `buildParser` UP-TO-DATE and the plugin ships a jar with the stale runner — exactly the silent-staleness class the root build's grammar tasks guard against with precise input/output declarations. `testParser` (lines 50-52) has the same gap: `test.hxml` also lists the `-resource`, but the task's inputs omit the resources dir (`integrationTestParser` gets it right at line 67). Add `inputs.dir("src/main/resources")` to both.

### should-fix — tools/LimeProjectParser/README.md:60-64
The "Planned" section says plugin integration ("`HaxeLimeProjectInfoService` invoking the jar ... instead of `haxelib run lime display`") is future work, but that service exists and already does exactly this: `src/main/java/com/intellij/plugins/haxe/v2/buildtools/HaxeLimeProjectInfoService.java` invokes the bundled jar with `--target/--haxe/--haxelib` plus seed defines (line 180-186), and the root `build.gradle.kts:306-307` bundles the jar. CLAUDE.md requires READMEs be kept current when behaviour changes — move that bullet out of "Planned" (the compat-matrix validation lane bullet appears genuinely still planned and can stay).

### should-fix — tools/LimeProjectParser/src/main/haxe/limeparser/HxpEvaluator.hx:26-59
`evaluate` creates a temp directory (`createTempDirectory`, line 83-90) and copies the user's script plus HxpRunner.hx into it, but nothing ever deletes it — neither on success nor in the `catch`. Every `.hxp` evaluation leaks a directory, and the IDE service re-runs the tool on each cache miss/retry, so a long IDE session accumulates temp dirs containing copies of the user's project file. Wrap the body so the directory (and its two files) is removed in a `finally`-equivalent path before returning.

### should-fix — tools/LimeProjectParser/src/main/haxe/limeparser/ProjectXmlEvaluator.hx:148,219-220
Fully-qualified `haxe.io.Path.extension(...)`, `haxe.io.Path.isAbsolute(path)` and `haxe.io.Path.join(...)` in code; the file imports nothing from `haxe.io` and there is no name collision — CLAUDE.md: "No fully-qualified names in code — import instead." Same one-off pattern in HxpEvaluator.hx:30 (`haxe.Resource.getString`) and HxpRunner.hx:72 (`haxe.Json.stringify`), both in files that already carry an import block.

### minor — tools/LimeProjectParser/src/main/haxe/limeparser/HxpEvaluator.hx:44-46
`process.stdout.readAll()` runs to EOF before `process.stderr.readAll()` starts. If the spawned haxe fills the stderr pipe buffer (~64K — a large error dump from a broken `.hxp` or a macro trace) while the parent is still blocked on stdout, both sides block and the tool hangs — forever when run standalone, until the 60s `DISPLAY_TIMEOUT_MS` kill when run from the IDE. `HaxelibLookup.resolve` (HaxelibLookup.hx:33-34) has the same shape but `haxelib path` output is small. Drain the two pipes concurrently or read stderr first for the failure path.

### minor — tools/LimeProjectParser/src/test/haxe/limeparser/IntegrationMain.hx:149-156
`createTempDirectory` is a verbatim copy of HxpEvaluator.hx:83-90 (same TEMP → TMPDIR → `/tmp` fallback and random-suffix scheme). Duplication rule: one named home — make the main-code helper the shared one (it is package-visible territory; the test already calls other main classes directly). Similarly the `name[=value]` split (`split("=")` + `slice(1).join("=")`) is spelled in Main.hx:47-48 and ProjectXmlEvaluator.hx:187-188 and could be one helper. (HxpRunner.hx:31-32 legitimately owns its copy — it compiles in the user's lime context, never against the tool's sources.)

### minor — tools/LimeProjectParser/src/main/haxe/limeparser/Main.hx:14,24
Both the class doc and the printed usage string list only `--target`, `--command` and `-D`, but the parser also accepts `--haxe` and `--haxelib` (lines 42-45) — and the IDE service passes both (HaxeLimeProjectInfoService.java:182). Document them in both places so the usage line matches what the tool actually takes.

### minor — tools/LimeProjectParser/src/main/haxe/limeparser/ProjectXmlEvaluator.hx:147-148
`var type = element.exists("type") ? substitute(...) : element.exists("path") ? haxe.io.Path.extension(...).toLowerCase() : "";` is a nested multi-line ternary inside the case — a multi-line value that wants its pieces named (e.g. resolve the declared type and the path-derived type into locals, then pick). Also the `default:` comment at line 154 still lists `app` among elements that "carry no build configuration", but `case "app"` above (line 140-142) now extracts `path`/`file` — drop `app` from the list.
