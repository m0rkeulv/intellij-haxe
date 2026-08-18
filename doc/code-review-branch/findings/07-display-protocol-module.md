# 07-display-protocol-module

### should-fix — display-protocol/src/main/java/com/intellij/plugins/haxe/display/client/HaxeDisplayClient.java:177
`rpc` misreports a compiler-error response as a malformed one. When the server
answers with the fatal-error marker, the compiler's error text arrives as the
PAYLOAD (pinned by `TransportFramingTest.plainCompilerErrorOutputBecomesPayloadWithTheErrorFlag`),
so the `payload().isEmpty()` guard passes, `DisplayJson.unwrap` fails to parse
the text as JSON, and the caller gets `"Malformed display response: Main.hx:7 ..."`
— wrong classification, and the `failureDetail` log-tail path built exactly for
this case is skipped. Fix: when parsing the payload fails and
`response.hasError()` is true, throw `"Display request '<method>' failed: "
+ failureDetail(response)` (or include the payload as the compiler message)
instead of the "Malformed" wording; keep "Malformed" for the genuinely
unparseable no-error case.

### minor — display-protocol/src/test/java/com/intellij/plugins/haxe/display/client/DisplayJsonTest.java:24
`DisplayJson.encodeRequest("display/hover", java.util.Map.of("offset", 42))`
uses a fully-qualified name; the "No fully-qualified names in code" rule allows
them only for genuine collisions and there is none here (`Map` is not imported
at all — the sibling `List` import shows the convention). Import `java.util.Map`
and write `Map.of("offset", 42)`.

### minor — display-protocol/src/main/java/com/intellij/plugins/haxe/display/client/HaxeDisplayClient.java:94
Consumer-less API without a TODO. `hover(...)` (line 94), `definition(...)`
(line 99) and the batch `diagnostics(List, Map)` overload (line 79) have no
caller anywhere in the plugin — only this module's own tests exercise them.
Likewise `DisplayMethods` declares 11 constants nothing references
(`COMPLETION`, `COMPLETION_ITEM_RESOLVE`, `GOTO_IMPLEMENTATION`,
`GOTO_TYPE_DEFINITION`, `DETERMINE_PACKAGE`, `SIGNATURE_HELP`, `DEFINES`,
`SERVER_READ_CLASS_PATHS`, `SERVER_CONFIGURE`, `SERVER_FILES`,
`SERVER_MODULE_CREATED`). Mirroring the std protocol's full method list is a
defensible design for a wire-constants class (its javadoc says as much), but
the client METHODS are "designed for later" without a marker — per the
checklist, each needs a one-line TODO naming the feature that will consume it
(hover documentation, batch diagnostics sweep, ...) or removal until then.

### minor — display-protocol/src/main/java/com/intellij/plugins/haxe/display/client/HaxeDisplayClient.java:18
The class javadoc says "Instances are cheap and stateless: create one per
server address", but the class carries a mutable `observer` field with a
setter (`setObserver`, line 45) that `HaxeCompilerDisplayService` uses. The
comment misstates what the code does now — drop "stateless" (e.g. "cheap;
create one per server address") or note the observer as the one piece of
state.

### minor — display-protocol/src/test/java/com/intellij/plugins/haxe/display/client/LiveDisplayServerTest.java:249
`waitUntilAccepting` can spin forever: on a SUCCESSFUL request whose response
has an empty payload and no logs, the loop neither sleeps nor checks the
deadline (both live only in the `catch`), and `@BeforeAll` has no timeout.
Unlikely against a healthy `haxe --wait`, but a wedged server that accepts and
closes silently would hang the suite. Check the deadline (and sleep) on every
iteration, not just the exception path.

### minor — display-protocol/src/main/java/com/intellij/plugins/haxe/display/protocol/JsonTypeRef.java:53
`Stream.of(args.path("args")).flatMap(JsonNode::valueStream)` wraps a single
node in a stream only to flatten it back out. `args.path("args").valueStream()`
is the same pipeline stated directly — one call instead of a two-step
indirection the reader has to unpick.

### minor — display-protocol/src/main/java/com/intellij/plugins/haxe/display/client/HaxeDisplayClient.java:114
Multi-line values inside calls. The `metadata` (114-116), `module` (137-139)
and `typeBlueprint` (145-148) request bodies are wrapping `Map.of(...)`
constructions nested inside the `rpc(...)` argument list; per the structural
rule a wrapping argument gets extracted into a named local
(`Map<String, Object> params = Map.of(...); rpc(baseArgs, METHOD, params)`),
leaving the call itself on one line. Same file line 82: the four-step lambda
in the batch `diagnostics` overload builds an entry map inline — a non-trivial
lambda that wants to be a private `diagnosticsEntry(file, contents)` method
used as `entries.add(diagnosticsEntry(...))`.

### minor — display-protocol/build.gradle.kts:13
The module comment promises "DTOs mirroring std haxe.display.*, the
null-terminated socket transport ... and the type-blueprint cache", but no
cache lives in this module — `TypeBlueprint` is a plain DTO and the caching
sits in `HaxeCompilerResolveService` under `src/main/java/.../v2/display/`.
Comments state what the code does now; drop "and the type-blueprint cache" or
reword to "the type-blueprint DTOs".
