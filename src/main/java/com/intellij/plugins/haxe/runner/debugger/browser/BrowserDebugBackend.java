package com.intellij.plugins.haxe.runner.debugger.browser;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.application.PathManager;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.dap.client.DapClient;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapBackend;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapDebugProcess;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.Request;
import com.intellij.plugins.haxe.runner.debugger.dap.protocol.requests.ConfiguredLaunchRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The browser backend (Firefox for now): resolves the pinned
 * vscode-firefox-debug adapter through the {@link AdapterStore} (download on
 * first use, SHA-256-verified), spawns it on the user's node in DAP TCP
 * server mode, optionally serves the content directory over the plugin's own
 * loopback {@link ContentHttpServer}, and connects the shared DAP client.
 * The BROWSER is launched by the adapter (the launch request carries the
 * url/file and optional executable), so the runner spawns no debuggee.
 *
 * Wire behaviour pinned by FirefoxAdapterLiveProbe: initialize needs
 * pathFormat=path (the debug process sends it), the initialized event arrives
 * only after launch, there is no configurationDone, and breakpoint
 * verification upgrades lazily via breakpoint events.
 *
 * All heavy work (download, server, spawn) happens in {@link #connect()} on
 * the debug process's request thread — never the EDT.
 */
public class BrowserDebugBackend implements DapBackend {
  private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
  private static final long CONNECT_RETRY_WINDOW_MILLIS = 10_000;
  private static final long ADAPTER_KILL_WAIT_SECONDS = 2;
  private static final int MIN_NODE_MAJOR = 18;
  /** Serve mode: delay of the one-shot first-page refresh (see connect()). */
  private static final int FIRST_PAGE_REFRESH_SECONDS = 2;

  private final String configuredNodePath;
  private final String configuredBrowserExecutable;
  private final boolean serveContent;
  private final Path contentRoot; // when serving
  private final String url;       // when not serving

  private volatile ContentHttpServer contentServer;
  private volatile Process adapterProcess;
  private volatile BufferedReader adapterStdout;
  private volatile Map<String, Object> launchConfig;

  public BrowserDebugBackend(String configuredNodePath,
                             String configuredBrowserExecutable,
                             boolean serveContent,
                             Path contentRoot,
                             String url) {
    this.configuredNodePath = configuredNodePath;
    this.configuredBrowserExecutable = configuredBrowserExecutable;
    this.serveContent = serveContent;
    this.contentRoot = contentRoot;
    this.url = url;
  }

  @Override
  public DapClient connect() throws IOException {
    Path node = locateNode();
    requireModernNode(node);
    Path adapterEntry = new AdapterStore(adapterStoreRoot())
      .resolveEntry(AdapterPin.FIREFOX, null);

    String targetUrl = url;
    if (serveContent) {
      contentServer = new ContentHttpServer(contentRoot);
      targetUrl = contentServer.getBaseUrl();
      // load-time breakpoint support (live-verified, FirefoxAdapterLiveProbe
      // variant K): a tab's FIRST load always races the debugger attach (the
      // JS thread only exists once scripts run), so the first page response
      // gets a one-shot meta-refresh — the first load binds the source map
      // and breakpoints, and the automatic reload runs the page again with
      // everything armed, stopping in load-time code like main().
      contentServer.refreshFirstPage(FIRST_PAGE_REFRESH_SECONDS);
    }
    launchConfig = buildLaunchConfig(targetUrl);

    BrowserAdapterLauncher.LaunchedAdapter launched = BrowserAdapterLauncher.launch(node, adapterEntry);
    adapterProcess = launched.process();
    adapterStdout = launched.stdout();
    return connectWithRetry(launched.port());
  }

  // The adapter announces its port slightly BEFORE the listener accepts
  // (live-observed); retry inside a short window instead of failing the session.
  private static DapClient connectWithRetry(int port) throws IOException {
    long deadline = System.currentTimeMillis() + CONNECT_RETRY_WINDOW_MILLIS;
    IOException last = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        return DapClient.connect("127.0.0.1", port, CONNECT_TIMEOUT_MILLIS);
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(100);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while connecting to the debug adapter", ie);
        }
      }
    }
    throw last != null ? last : new IOException("Could not connect to the debug adapter");
  }

  private Map<String, Object> buildLaunchConfig(String targetUrl) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("request", "launch");
    // native file for file-less setups is possible ("file"), but both config
    // modes here produce a URL (our server's, or the user's)
    config.put("url", targetUrl);
    if (serveContent) {
      // maps served urls back to the content directory for the source maps
      config.put("webRoot", contentRoot.toString());
    }
    if (!configuredBrowserExecutable.isBlank()) {
      config.put("firefoxExecutable", configuredBrowserExecutable);
    }
    return config;
  }

  // --- node discovery ---

  private Path locateNode() throws IOException {
    if (!configuredNodePath.isBlank()) {
      Path node = Path.of(configuredNodePath);
      if (!Files.isRegularFile(node)) {
        throw new IOException(HaxeDebuggerBundle.message("browser.runner.node.configured.missing", configuredNodePath));
      }
      return node;
    }
    File onPath = PathEnvironmentVariableUtil.findInPath(nodeBinaryName());
    if (onPath == null) {
      throw new IOException(HaxeDebuggerBundle.message("browser.runner.node.not.found"));
    }
    return onPath.toPath();
  }

  private static String nodeBinaryName() {
    return System.getProperty("os.name", "").toLowerCase().contains("win") ? "node.exe" : "node";
  }

  // `node --version` prints e.g. v24.18.0; anything below 18 (EOL) is refused
  // with the same install hint as a missing node.
  private static void requireModernNode(Path node) throws IOException {
    String version;
    try {
      Process probe = new ProcessBuilder(node.toString(), "--version").redirectErrorStream(true).start();
      version = new String(probe.getInputStream().readAllBytes()).trim();
      if (!probe.waitFor(10, TimeUnit.SECONDS)) {
        probe.destroyForcibly();
        throw new IOException(HaxeDebuggerBundle.message("browser.runner.node.broken", node, "--version timed out"));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while checking node", e);
    }
    int major = parseMajor(version);
    if (major < 0) {
      throw new IOException(HaxeDebuggerBundle.message("browser.runner.node.broken", node, version));
    }
    if (major < MIN_NODE_MAJOR) {
      throw new IOException(HaxeDebuggerBundle.message("browser.runner.node.too.old", version, MIN_NODE_MAJOR));
    }
  }

  private static int parseMajor(String version) {
    if (!version.startsWith("v")) {
      return -1;
    }
    int dot = version.indexOf('.');
    try {
      return Integer.parseInt(version.substring(1, dot > 0 ? dot : version.length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** The pinned-adapter cache: {@code <ide-system>/haxe/debug-adapters}. */
  private static Path adapterStoreRoot() {
    return Path.of(PathManager.getSystemPath(), "haxe", "debug-adapters");
  }

  // --- session behaviour (wire facts from the live probe) ---

  @Override
  public void onConnected(DapDebugProcess process) {
    BufferedReader reader = adapterStdout;
    adapterStdout = null;
    if (reader == null) {
      return;
    }
    Thread gobbler = new Thread(() -> {
      try (BufferedReader stdout = reader) {
        String line;
        while ((line = stdout.readLine()) != null) {
          process.printSystem("[adapter] " + line + "\n");
        }
      } catch (IOException ignored) {
        // adapter ended
      }
    }, "Browser adapter output");
    gobbler.setDaemon(true);
    gobbler.start();
  }

  @Override
  public boolean requiresLaunchRequest() {
    return true;
  }

  @Override
  public Request launchRequest() {
    return ConfiguredLaunchRequest.of(launchConfig);
  }

  @Override
  public boolean initializedEventAfterLaunch() {
    return true; // the adapter signals readiness once the browser side is up
  }

  @Override
  public boolean sendsConfigurationDone() {
    return false; // the adapter reports supportsConfigurationDoneRequest=false
  }

  @Override
  public boolean supportsExceptionFilters() {
    return true;
  }

  // vscode-firefox-debug's filter vocabulary: "all" / "uncaught". There is no
  // separate critical category in a JS runtime; critical maps to uncaught.
  @Override
  public String anyThrowFilterId() {
    return "all";
  }

  @Override
  public String criticalFilterId() {
    return "uncaught";
  }

  // The adapter matches breakpoint paths LITERALLY against native paths; the
  // IDE's forward-slash VFS paths silently never bind (live-verified by
  // FirefoxAdapterLiveProbe.breakpointPathSeparatorSensitivity).
  @Override
  public String breakpointSourcePath(String vfsPath) {
    return vfsPath.replace('/', java.io.File.separatorChar);
  }

  @Override
  public boolean supportsSmartStepInto() {
    return false; // no stepInTargets and no intellij/stepIntoFunction in the adapter
  }

  @Override
  public boolean supportsToStringRendering() {
    return false;
  }

  @Override
  public String startupHint() {
    return HaxeDebuggerBundle.message("browser.runner.startup.hint");
  }

  @Override
  public void close() {
    ContentHttpServer server = contentServer;
    contentServer = null;
    if (server != null) {
      server.close();
    }
    Process adapter = adapterProcess;
    adapterProcess = null;
    if (adapter != null) {
      // reap the WHOLE tree: killing node does not kill the Firefox it
      // spawned, and when the graceful DAP disconnect did not happen (forced
      // teardown) every session would otherwise leak a headless browser
      // (live-observed: 122 zombies after a probe day)
      adapter.descendants().forEach(ProcessHandle::destroyForcibly);
      adapter.destroy();
      try {
        if (!adapter.waitFor(ADAPTER_KILL_WAIT_SECONDS, TimeUnit.SECONDS)) {
          adapter.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
