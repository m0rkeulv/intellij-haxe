package com.intellij.plugins.haxe.display.client;

import com.intellij.plugins.haxe.display.protocol.*;
import com.intellij.plugins.haxe.display.protocol.server.HaxeServerContext;
import com.intellij.plugins.haxe.display.protocol.server.ModuleInfo;
import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import com.intellij.plugins.haxe.display.transport.DisplayResponse;
import com.intellij.plugins.haxe.display.transport.HaxeDisplayTransport;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

  /// Which compiler to drive: the default PATH haxe, or an alternative
  /// binary via -PdisplayTestHaxe (e.g. a haxe 5 preview).
  private static final String HAXE_EXE = System.getProperty("display.test.haxe", "haxe");

  private static Process server;
  private static int port;
  private static InitializeResult.SemVer serverVersion;
  private static HaxeDisplayClient client;
  private static List<String> baseArgs;
  private static String fixtureFile;

  @BeforeAll
  static void startServer() throws Exception {
    assumeTrue(haxeAvailable(), "haxe (" + HAXE_EXE + ") not runnable - skipping live display test");
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    server = new ProcessBuilder(HAXE_EXE, "--wait", String.valueOf(port))
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start();
    Path fixture = workDir.resolve("Live.hx");
    Files.writeString(fixture, FIXTURE);
    fixtureFile = fixture.toString();
    baseArgs = List.of("--cwd", workDir.toString(), "-cp", ".", "-main", "Live", "-js", "out.js", "--no-output");
    client = new HaxeDisplayClient("127.0.0.1", port);
    waitUntilAccepting();
    serverVersion = client.initialize(baseArgs).haxeVersion();
    System.out.println("[live] driving haxe " + serverVersion);
  }

  /// The capability helpers below name the haxe 5 behavior changes one by
  /// one - a test gates on the capability it exercises, never on a bare
  /// version check borrowed from an unrelated capability.
  private static boolean isHaxe5OrNewer() {
    return serverVersion.major() >= 5;
  }

  /// Haxe 5+ populates the LSP-style diagnostic code with -w warning identifiers.
  private static boolean sendsDiagnosticCodes() {
    return isHaxe5OrNewer();
  }

  /// Haxe 5 renames the removable-code kind to ReplaceableCode and may supply newCode.
  private static boolean sendsReplaceableCode() {
    return isHaxe5OrNewer();
  }

  /// Haxe 5 answers server/module for defineType-created modules too.
  private static boolean servesDefinedModuleInfo() {
    return isHaxe5OrNewer();
  }

  /// Haxe 5 (preview) serializes server/type member types BEFORE forcing lazy
  /// typing, so fields arrive as unresolved TMono; 4.x answers concrete types.
  /// Names and shapes are reliable on both - only type resolution differs.
  private static boolean blueprintTypesResolved() {
    return !isHaxe5OrNewer();
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
  @DisplayName("deprecation warning identification per compiler generation")
  public void deprecationWarningIdentificationPerCompilerGeneration() throws Exception {
    String withOldEnumAbstract = FIXTURE
      .replace("static function main() trace(new Live().shout());",
               "static function main() { trace(new Live().shout()); trace(Old.A); }")
      + """

      @:enum abstract Old(Int) {
      	var A = 1;
      }
      """;
    client.invalidate(baseArgs, fixtureFile);
    List<FileDiagnostics> results = client.diagnostics(baseArgs, fixtureFile, withOldEnumAbstract);

    List<Diagnostic> all = results.stream().flatMap(file -> file.diagnostics().stream()).toList();
    for (Diagnostic diagnostic : all) {
      System.out.println("[live] diag kind=" + diagnostic.kind() + " severity=" + diagnostic.severity()
                         + " code=" + diagnostic.code() + " args=" + diagnostic.args());
    }
    Diagnostic deprecation = all.stream()
      .filter(diagnostic -> diagnostic.messageArg().contains("deprecated"))
      .findFirst()
      .orElse(null);
    assertNotNull(deprecation, "@:enum abstract must surface a deprecation warning");
    System.out.println("[live] deprecation message = " + deprecation.messageArg());
    System.out.println("[live] deprecation code    = " + deprecation.code());
    System.out.println("[live] deprecation args    = " + deprecation.args());

    // What identification the wire offers, per compiler generation: 4.x sends
    // prose only; 5+ fills code with the SPECIFIC -w warning identifier
    // (WDeprecatedEnumAbstract here, not just the WDeprecated class).
    if (sendsDiagnosticCodes()) {
      assertNotNull(deprecation.code(), "haxe 5+ identifies warnings by code");
      assertTrue(deprecation.code().startsWith("WDeprecated"),
                 "deprecation codes share the WDeprecated prefix, got " + deprecation.code());
    } else {
      assertNull(deprecation.code(), "haxe 4.x sends no code ids");
    }

    // The flag-side counterpart: -w -WDeprecated suppresses the warning CLASS
    // at the request level, so class-based filtering is possible without ids.
    List<String> suppressed = new ArrayList<>(baseArgs);
    suppressed.add("-w");
    suppressed.add("-WDeprecated");
    client.invalidate(suppressed, fixtureFile);
    List<FileDiagnostics> filtered = client.diagnostics(suppressed, fixtureFile, withOldEnumAbstract);
    boolean stillWarned = filtered.stream()
      .flatMap(file -> file.diagnostics().stream())
      .anyMatch(diagnostic -> diagnostic.messageArg().contains("deprecated"));
    System.out.println("[live] with -w -WDeprecated stillWarned=" + stillWarned);
    assertFalse(stillWarned, "-w -WDeprecated must suppress the deprecation warning class");
  }

  @Test
  @Timeout(60)
  @DisplayName("removable code range for an unused local keeps the initializer")
  public void removableCodeRangeForAnUnusedLocalKeepsTheInitializer() throws Exception {
    String withUnusedVar = FIXTURE.replace(
      "static function main() trace(new Live().shout());",
      """
      static function main() {
      		var dummy:Int = 0;
      		trace(new Live().shout());
      	}""");
    client.invalidate(baseArgs, fixtureFile);
    List<FileDiagnostics> results = client.diagnostics(baseArgs, fixtureFile, withUnusedVar);

    Diagnostic removable = results.stream()
      .flatMap(file -> file.diagnostics().stream())
      .filter(diagnostic -> diagnostic.kind() == DiagnosticKind.REMOVABLE_CODE)
      .findFirst()
      .orElse(null);
    assertNotNull(removable, "the unused local must surface as REMOVABLE_CODE");
    System.out.println("[live] removable args = " + removable.args());
    System.out.println("[live] display range  = " + removable.range());

    // The wire fact the remove quick fix relies on: the args' removal span
    // covers the BINDING ("var dummy:Int = ") and deliberately KEEPS the
    // initializer expression - `var x = sideEffect();` must not lose the call.
    Range removal = removable.removableRangeArg();
    assertNotNull(removal, "removable-code args must carry the removal range");
    if (sendsReplaceableCode()) {
      System.out.println("[live] haxe5 replaceable newCode = " + removable.args().path("newCode"));
      return;
    }
    String varLine = "var dummy:Int = 0;";
    List<String> lines = withUnusedVar.lines().toList();
    int varLineIndex = lines.indexOf(lines.stream().filter(l -> l.contains(varLine)).findFirst().orElseThrow());
    int initializerColumn = lines.get(varLineIndex).indexOf("0;");
    boolean initializerKept = removal.end().line() == varLineIndex && removal.end().character() <= initializerColumn;
    assertTrue(initializerKept, "expected the removal range to end before the initializer, got " + removal);
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
      .filter(context -> contextHasModule(baseArgs, context, "Live"))
      .findFirst().orElse(null);
    assertNotNull(modulesContext, "a context holding the compiled module must exist");

    TypeBlueprint blueprint = client.typeBlueprint(baseArgs, modulesContext.signature(), "Live", "Live");
    assertEquals("class", blueprint.kind());
    assertNotNull(blueprint.findMember("label"), "members must be listed by name");
    assertNotNull(blueprint.findMember("shout"), "members must be listed by name");
    if (blueprintTypesResolved()) {
      assertEquals("String", blueprint.findMember("label").type().dotPath());
      assertEquals("() -> String", blueprint.findMember("shout").type().presentable());
    }
    else {
      System.out.println("[live] unresolved blueprint member type kinds: label="
                         + blueprint.findMember("label").type().kind()
                         + " shout=" + blueprint.findMember("shout").type().presentable());
    }
  }

  // A macro-defined type: exists in NO source file, only in the compiler's
  // post-macro world - the case the IDE's type catalog serves.
  // defineType runs inside onAfterInitMacros: haxe 5 forbids it straight from
  // an initialization macro, and the deferred form works on 4.2+ as well
  private static final String GEN_MACRO = """
    import haxe.macro.Context;
    class GenMacro {
    	public static function define() {
    		Context.onAfterInitMacros(() -> Context.defineType({
    			pack: ["gen"],
    			name: "GeneratedThing",
    			pos: Context.currentPos(),
    			kind: TDClass(),
    			fields: [{
    				name: "tag",
    				access: [APublic],
    				kind: FVar(macro :String, macro "gen"),
    				pos: Context.currentPos()
    			}, {
    				name: "make",
    				access: [APublic, AStatic],
    				kind: FFun({args: [], ret: macro :String, expr: macro return "made"}),
    				pos: Context.currentPos()
    			}]
    		}));
    	}
    }
    """;
  private static final String GEN_MAIN = """
    class LiveGen {
    	static function main() trace(gen.GeneratedThing.make());
    }
    """;

  @Test
  @Timeout(90)
  @DisplayName("macro defined type appears in modules and blueprints after a compile")
  public void macroDefinedTypeAppearsInModulesAndBlueprintsAfterACompile() throws Exception {
    Files.writeString(workDir.resolve("GenMacro.hx"), GEN_MACRO);
    Files.writeString(workDir.resolve("LiveGen.hx"), GEN_MAIN);
    List<String> genArgs = List.of("--cwd", workDir.toString(), "-cp", ".", "-main", "LiveGen",
                                   "--macro", "GenMacro.define()", "-js", "gen.js", "--no-output");

    // the module cache is EMPTY until a real compile - the wire fact the
    // IDE's context warm-up compile exists for
    assertNull(typedContextHolding(genArgs, "LiveGen"), "no module cache before a compile");

    DisplayResponse compiled = HaxeDisplayTransport.request("127.0.0.1", port, genArgs, 30_000);
    assertFalse(compiled.hasError(), "fixture compile must succeed: " + compiled.payload());

    HaxeServerContext context = typedContextHolding(genArgs, "LiveGen");
    assertNotNull(context, "the typed context must list the compiled module");
    assertEquals("after_init_macros", context.desc(), "the IDE filters typed contexts by this desc");

    // a defineType-created module is INVISIBLE to the flat listing and has no
    // ModuleInfo of its own; it surfaces only in the dependency lists of the
    // modules using it - the discovery path the IDE's type catalog walks
    List<String> listed = client.modules(genArgs, context.signature());
    assertFalse(listed.contains("gen.GeneratedThing"), "server/modules must not list the defined module");
    ModuleInfo userInfo = client.module(genArgs, context.signature(), "LiveGen");
    assertTrue(userInfo.dependencies().contains("gen.GeneratedThing"),
               "the using module's dependencies expose the defined module");
    assertFalse(userInfo.sign().isEmpty(), "sign drives the catalog's incremental refresh");
    if (servesDefinedModuleInfo()) {
      ModuleInfo definedInfo = client.module(genArgs, context.signature(), "gen.GeneratedThing");
      assertFalse(definedInfo.sign().isEmpty(), "haxe 5 serves ModuleInfo for a defined module");
    }
    else {
      assertThrows(Exception.class,
                   () -> client.module(genArgs, context.signature(), "gen.GeneratedThing"),
                   "haxe 4 server/module rejects a defined module");
    }

    // server/type answers on both generations - blueprints are how the
    // defined type's members become visible
    TypeBlueprint blueprint = client.typeBlueprint(genArgs, context.signature(), "gen.GeneratedThing", "GeneratedThing");
    assertNotNull(blueprint.findMember("tag"), "generated members must be listed by name");
    assertNotNull(blueprint.findMember("make"), "generated members must be listed by name");
    if (blueprintTypesResolved()) {
      assertEquals("String", blueprint.findMember("tag").type().dotPath());
      assertEquals("() -> String", blueprint.findMember("make").type().presentable());
    }
  }

  /** The server context whose module cache holds {@code module}, or null (also while no cache exists at all). */
  private static HaxeServerContext typedContextHolding(List<String> args, String module) {
    try {
      for (HaxeServerContext context : client.contexts(args)) {
        if (contextHasModule(args, context, module)) return context;
      }
    } catch (Exception ignored) {
      // a fresh server rejects context listing until something compiled
    }
    return null;
  }

  private static boolean contextHasModule(List<String> args, HaxeServerContext context, String module) {
    try {
      return client.modules(args, context.signature()).contains(module);
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean haxeAvailable() {
    try {
      Process process = new ProcessBuilder(HAXE_EXE, "--version")
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
        // any completed exchange proves the server accepts; the RESPONSE may
        // legitimately be empty (haxe 5 answers the legacy --version request
        // with a bare close)
        HaxeDisplayTransport.request("127.0.0.1", port, List.of("--version"), 5_000);
        return;
      } catch (Exception e) {
        if (System.currentTimeMillis() > deadline) {
          throw e;
        }
        Thread.sleep(200);
      }
    }
  }
}
