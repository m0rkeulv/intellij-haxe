# display-protocol

Client for the Haxe compiler's JSON-RPC display protocol, spoken against the
same `haxe --wait <port>` compilation server the plugin already runs for
builds. DTOs mirror the std `haxe.display.*` typedefs; the transport speaks
the compiler's null-terminated socket form. Everything below was verified
live against haxe 4.3.7.

## Wire protocol (`--wait <port>` TCP mode)

One socket per request:

1. connect, write every compiler argument followed by `\n`, then a single
   `\0` byte;
2. read until the server closes the connection;
3. classify each response line by its first byte: `0x01` = log line (embedded
   newlines appear as further `0x01` bytes), `0x02` = fatal-error marker, and
   everything else is payload. For a display request the payload is the raw
   JSON-RPC response envelope.

A display request is the build's normal argument list plus
`--display <json-rpc-payload>`. The response nests twice:
`{"jsonrpc":"2.0","id":1,"result":{"result":<data>,"timestamp":...}}`
(the inner wrapper is std `Response<T>`).

Facts that shape the client:

- **The server processes one connection at a time.** Concurrent sockets queue
  in the OS listen backlog and are answered in order (~15 ms per request on
  localhost, cache warm). Nothing is dropped; no client-side queue is needed
  for correctness.
- **Never hold a connection open.** A half-open connection (arguments sent,
  no `\0`) blocks every other client — including builds — until a ~5 s
  server-side read timeout. Hence: no connection pool, strict
  connect-request-close.
- **No batching.** A JSON-RPC batch array is rejected (`-32600`), a second
  `--display` argument is ignored, and `--next` short-circuits after the
  first display request. One request per connection. Method-level batching
  covers the real need: `display/diagnostics` takes `fileContents` (many
  files) or empty params (whole project).
- **Result positions are 0-based** (line and character), although the
  `Position.hx` docstrings claim 1-based — the values are converted for LSP.
  The `offset` in request params is a unicode character offset into the file.
- **Unsaved buffers** go in the request: `contents` on `PositionParams` and
  `DiagnosticsParams`. BUT once a module is cached, `contents` alone is
  IGNORED — the server sees an unchanged mtime and serves the cached result.
  Send `server/invalidate {file}` whenever the editor buffer diverges from
  disk (vshaxe does this on every document change); the next request then
  reparses from the supplied contents. Saved files need nothing: mtime is
  re-checked per request.
- **`server/*` introspection needs a compile first.** The module cache is
  populated by an actual compile through the server (e.g.
  `-main X -js out.js --no-output`) with the same argument signature;
  display requests alone leave `server/modules` empty. Flow:
  compile → `server/contexts` (the `after_init_macros` context holds the
  modules) → `server/module` (dependencies/dependents, for invalidation) →
  `server/type` (the complete post-macro type: every field with its
  `JsonType` — macro-generated members included).
- **`Context.defineType` modules are invisible to `server/modules`** and
  `server/module` rejects them ("Compiler error") — they surface ONLY in the
  `dependencies` lists of the modules using them. `server/type` on the
  defined dot path answers normally. Pinned by
  `LiveDisplayServerTest.macroDefinedTypeAppearsInModulesAndBlueprintsAfterACompile`;
  the IDE's type catalog discovers generated types through this dependency
  sweep.
- Gate features on the method list returned by `initialize`
  (`display/diagnostics` is haxe 4.3+).
