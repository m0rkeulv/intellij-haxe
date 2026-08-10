package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.*;
import com.intellij.plugins.haxe.display.protocol.server.*;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.display.transport.DisplayResponse;
import com.intellij.plugins.haxe.display.transport.HaxeDisplayTransport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/// Typed requests against one `haxe --wait <port>` server. Every call is
/// one connect-request-close exchange; `baseArgs` is the build's normal
/// argument list (the display request rides on it — it defines the cache
/// context the server answers from). Instances are cheap and stateless: create
/// one per server address.
public class HaxeDisplayClient {

  /** Notified after every request round-trip — the IDE records per-server metrics from it. */
  public interface RequestObserver {
    void afterRequest(String method, long millis, boolean success);
  }

  private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

  private final String host;
  private final int port;
  private final int readTimeoutMs;
  private RequestObserver observer;

  public HaxeDisplayClient(String host, int port) {
    this(host, port, DEFAULT_READ_TIMEOUT_MS);
  }

  public HaxeDisplayClient(String host, int port, int readTimeoutMs) {
    this.host = host;
    this.port = port;
    this.readTimeoutMs = readTimeoutMs;
  }

  public void setObserver(RequestObserver observer) {
    this.observer = observer;
  }

  // --- lifecycle / capability ---

  public InitializeResult initialize(List<String> baseArgs) throws DisplayRequestException {
    return DisplayJson.decodeInitialize(rpc(baseArgs, DisplayMethods.INITIALIZE, Map.of("supportsResolve", true)));
  }

  // --- display ---

  /** Diagnostics for one file; {@code contents} (nullable) overrides the on-disk text. */
  public List<FileDiagnostics> diagnostics(List<String> baseArgs, String file, String contents)
    throws DisplayRequestException {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("file", file);
    if (contents != null) {
      params.put("contents", contents);
    }
    return DisplayJson.decodeDiagnostics(rpc(baseArgs, DisplayMethods.DIAGNOSTICS, params));
  }

  /**
   * Whole-project diagnostics (empty params): every file the compile touches
   * that has problems. Unlike the per-file form, dependencies' own parse
   * errors surface here — the display parser is error-tolerant when a broken
   * file is merely depended upon.
   */
  public List<FileDiagnostics> projectDiagnostics(List<String> baseArgs) throws DisplayRequestException {
    return DisplayJson.decodeDiagnostics(rpc(baseArgs, DisplayMethods.DIAGNOSTICS, Map.of()));
  }

  /** Diagnostics for several files at once (entries without contents read from disk). */
  public List<FileDiagnostics> diagnostics(List<String> baseArgs, Map<String, String> fileContents)
    throws DisplayRequestException {
    List<Map<String, Object>> entries = new ArrayList<>();
    fileContents.forEach((file, contents) -> {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("file", file);
      if (contents != null) {
        entry.put("contents", contents);
      }
      entries.add(entry);
    });
    return DisplayJson.decodeDiagnostics(
      rpc(baseArgs, DisplayMethods.DIAGNOSTICS, Map.of("fileContents", entries)));
  }

  public HoverInfo hover(List<String> baseArgs, String file, int offset, String contents)
    throws DisplayRequestException {
    return DisplayJson.decodeHover(rpc(baseArgs, DisplayMethods.HOVER, positionParams(file, offset, contents)));
  }

  public List<Location> definition(List<String> baseArgs, String file, int offset, String contents)
    throws DisplayRequestException {
    return DisplayJson.decodeLocations(
      rpc(baseArgs, DisplayMethods.GOTO_DEFINITION, positionParams(file, offset, contents)));
  }

  public List<Location> references(List<String> baseArgs, String file, int offset, String contents,
                                   FindReferencesKind kind) throws DisplayRequestException {
    Map<String, Object> params = positionParams(file, offset, contents);
    params.put("kind", kind.wireValue());
    return DisplayJson.decodeLocations(rpc(baseArgs, DisplayMethods.FIND_REFERENCES, params));
  }

  /** The compiler's metadata registry: built-ins plus library-registered custom metadata. */
  public List<MetadataEntry> metadata(List<String> baseArgs) throws DisplayRequestException {
    return DisplayJson.decodeMetadataList(rpc(baseArgs, DisplayMethods.METADATA, Map.of(
      "compiler", true,
      "user", true)));
  }

  // --- server introspection (requires a compile with the same args first) ---

  public List<HaxeServerContext> contexts(List<String> baseArgs) throws DisplayRequestException {
    return DisplayJson.decodeContexts(rpc(baseArgs, DisplayMethods.SERVER_CONTEXTS, Map.of()));
  }

  /** Cache memory per compilation context; server-global, works with empty base args. */
  public ServerMemory serverMemory(List<String> baseArgs) throws DisplayRequestException {
    return DisplayJson.decodeServerMemory(rpc(baseArgs, DisplayMethods.SERVER_MEMORY, Map.of()));
  }

  public List<String> modules(List<String> baseArgs, String signature) throws DisplayRequestException {
    return DisplayJson.decodeStringList(
      rpc(baseArgs, DisplayMethods.SERVER_MODULES, Map.of("signature", signature)));
  }

  public ModuleInfo module(List<String> baseArgs, String signature, String modulePath)
    throws DisplayRequestException {
    return DisplayJson.decodeModule(rpc(baseArgs, DisplayMethods.SERVER_MODULE, Map.of(
      "signature", signature,
      "path", modulePath)));
  }

  /** The post-macro blueprint of one type — all members with resolved types. */
  public TypeBlueprint typeBlueprint(List<String> baseArgs, String signature, String modulePath, String typeName)
    throws DisplayRequestException {
    JsonNode data = rpc(baseArgs, DisplayMethods.SERVER_TYPE, Map.of(
      "signature", signature,
      "modulePath", modulePath,
      "typeName", typeName));
    return DisplayJson.decodeTypeBlueprint(typeName, data);
  }

  public void invalidate(List<String> baseArgs, String file) throws DisplayRequestException {
    rpc(baseArgs, DisplayMethods.SERVER_INVALIDATE, Map.of("file", file));
  }

  // --- plumbing ---

  private static Map<String, Object> positionParams(String file, int offset, String contents) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("file", file);
    params.put("offset", offset);
    if (contents != null) {
      params.put("contents", contents);
    }
    return params;
  }

  private JsonNode rpc(List<String> baseArgs, String method, Map<String, Object> params)
    throws DisplayRequestException {
    long start = System.nanoTime();
    boolean success = false;
    try {
      List<String> args = new ArrayList<>(baseArgs);
      args.add("--display");
      args.add(DisplayJson.encodeRequest(method, params));
      DisplayResponse response = HaxeDisplayTransport.request(host, port, args, readTimeoutMs);
      if (response.payload().isEmpty()) {
        throw new DisplayRequestException("Display request '" + method + "' got no result: " + failureDetail(response));
      }
      JsonNode result = DisplayJson.unwrap(response.payload());
      success = true;
      return result;
    }
    finally {
      if (observer != null) {
        observer.afterRequest(method, (System.nanoTime() - start) / 1_000_000, success);
      }
    }
  }

  /**
   * An empty response usually means the build context itself failed to
   * compile; the compiler explains WHY in its log lines (e.g. a define
   * override making a library uncompilable) — surface their tail instead of
   * a bare "empty response".
   */
  private static String failureDetail(DisplayResponse response) {
    List<String> lines = response.logs().stream()
      .filter(line -> !line.isBlank())
      .toList();
    if (lines.isEmpty()) {
      return response.hasError() ? "compiler reported an error" : "empty response";
    }
    String tail = String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
    return (response.hasError() ? "compiler error: " : "") + tail;
  }
}
