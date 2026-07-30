package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.*;
import com.intellij.plugins.haxe.display.protocol.server.HaxeServerContext;
import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import com.intellij.plugins.haxe.display.transport.DisplayResponse;
import com.intellij.plugins.haxe.display.transport.HaxeDisplayTransport;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The full flow against a real {@code haxe --wait} server. Self-skips when no
 * haxe executable is on the PATH.
 */
@DisplayName("Display protocol: live server (integration)")
public class LiveDisplayServerTest {

  private static final String FIXTURE = """
    class Live {
    	public var label:String = "hi";
    	public function new() {}
    	public function shout():String return label.toUpperCase();
    	static function main() trace(new Live().shout());
    }
    """;

  @TempDir
  static Path workDir;

  private static Process server;
  private static int port;
  private static HaxeDisplayClient client;
  private static List<String> baseArgs;
  private static String fixtureFile;

  @BeforeAll
  static void startServer() throws Exception {
    assumeTrue(haxeAvailable(), "haxe not on PATH - skipping live display test");
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    server = new ProcessBuilder("haxe", "--wait", String.valueOf(port))
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start();
    Path fixture = workDir.resolve("Live.hx");
    Files.writeString(fixture, FIXTURE);
    fixtureFile = fixture.toString();
    baseArgs = List.of("--cwd", workDir.toString(), "-cp", ".", "-main", "Live", "-js", "out.js", "--no-output");
    client = new HaxeDisplayClient("127.0.0.1", port);
    waitUntilAccepting();
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.destroy();
    }
  }

  @Test
  @Timeout(60)
  @DisplayName("initialize reports version and methods")
  public void initializeReportsVersionAndMethods() throws Exception {
    InitializeResult result = client.initialize(baseArgs);
    assertTrue(result.haxeVersion().major() >= 4);
    assertTrue(result.supports(DisplayMethods.HOVER));
  }

  @Test
  @Timeout(60)
  @DisplayName("diagnostics follow unsaved contents after an invalidate")
  public void diagnosticsFollowUnsavedContentsAfterAnInvalidate() throws Exception {
    List<FileDiagnostics> onDisk = client.diagnostics(baseArgs, fixtureFile, null);
    boolean cleanOnDisk = onDisk.isEmpty() || onDisk.get(0).diagnostics().isEmpty();
    assertTrue(cleanOnDisk, "fixture should have no diagnostics on disk");

    // Once a module is cached, `contents` alone is IGNORED - the server
    // serves the cached result (mtime unchanged). The file must be
    // invalidated whenever the buffer diverges from disk.
    client.invalidate(baseArgs, fixtureFile);

    String broken = FIXTURE.replace("label.toUpperCase()", "labell.toUpperCase()");
    List<FileDiagnostics> withContents = client.diagnostics(baseArgs, fixtureFile, broken);
    boolean flaggedUnresolved = withContents.stream()
      .flatMap(file -> file.diagnostics().stream())
      .anyMatch(diagnostic -> diagnostic.kind() == DiagnosticKind.UNRESOLVED_IDENTIFIER);
    assertTrue(flaggedUnresolved, "unsaved contents must drive the diagnostics");
  }

  @Test
  @Timeout(60)
  @DisplayName("hover definition and references resolve the fixture")
  public void hoverDefinitionAndReferencesResolveTheFixture() throws Exception {
    int labelUsage = FIXTURE.indexOf("label.toUpperCase") + 2;

    HoverInfo hover = client.hover(baseArgs, fixtureFile, labelUsage, null);
    assertNotNull(hover);
    assertEquals("String", hover.type().dotPath());

    List<Location> definitions = client.definition(baseArgs, fixtureFile, labelUsage, null);
    assertEquals(1, definitions.size());
    // declaration is on fixture line 2; wire ranges are 0-based
    assertEquals(1, definitions.get(0).range().start().line());

    int shoutDecl = FIXTURE.indexOf("function shout") + "function s".length();
    List<Location> references =
      client.references(baseArgs, fixtureFile, shoutDecl, null, FindReferencesKind.DIRECT);
    assertFalse(references.isEmpty(), "the call in main() must be found");
  }

  @Test
  @Timeout(60)
  @DisplayName("type blueprint hydrates after a compile through the server")
  public void typeBlueprintHydratesAfterACompileThroughTheServer() throws Exception {
    // populate the module cache: an actual compile with the same args
    HaxeDisplayTransport.request("127.0.0.1", port, baseArgs, 30_000);

    List<HaxeServerContext> contexts = client.contexts(baseArgs);
    HaxeServerContext modulesContext = contexts.stream()
      .filter(context -> contextHasModule(context, "Live"))
      .findFirst().orElse(null);
    assertNotNull(modulesContext, "a context holding the compiled module must exist");

    TypeBlueprint blueprint = client.typeBlueprint(baseArgs, modulesContext.signature(), "Live", "Live");
    assertEquals("class", blueprint.kind());
    assertEquals("String", blueprint.findMember("label").type().dotPath());
    assertEquals("() -> String", blueprint.findMember("shout").type().presentable());
  }

  private static boolean contextHasModule(HaxeServerContext context, String module) {
    try {
      return client.modules(baseArgs, context.signature()).contains(module);
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean haxeAvailable() {
    try {
      Process process = new ProcessBuilder("haxe", "--version")
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
      return process.waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /** The server needs a moment to bind; poll with a cheap request. */
  private static void waitUntilAccepting() throws Exception {
    long deadline = System.currentTimeMillis() + 15_000;
    while (true) {
      try {
        DisplayResponse response = HaxeDisplayTransport.request("127.0.0.1", port, List.of("--version"), 5_000);
        if (!response.payload().isEmpty() || !response.logs().isEmpty()) {
          return;
        }
      } catch (Exception e) {
        if (System.currentTimeMillis() > deadline) {
          throw e;
        }
        Thread.sleep(200);
      }
    }
  }
}
