package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.server.HaxeServerContext;
import com.intellij.plugins.haxe.display.protocol.server.ModuleInfo;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.lang.psi.indexes.filebased.extension.fqn.HaxeFullyQualifiedClassNameIndex;
import com.intellij.plugins.haxe.lang.psi.stubs.index.fqn.HaxeFullyQualifiedClassNameStubIndex;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * The compiler's post-macro type catalog: every type the compilation server
 * knows that exists in NO source file the IDE indexes — types created by
 * macros ({@code Context.defineType}/{@code defineModule}). The unified index
 * facades consult it as a third leg beside the stub and file-based indexes,
 * which gives completion, resolve and import candidates for generated types
 * without touching IntelliJ's index lifecycle: filling and invalidation are
 * driven by the server ({@code ModuleInfo.sign} diffs), never by the VFS.
 *
 * Queries are strictly cache-only (safe under the read lock); an empty
 * catalog schedules a background fill. The fill enumerates
 * {@code server/contexts} → {@code server/modules} → {@code server/module}
 * and keeps only types the FQN source indexes cannot find. The server's
 * module cache is empty until a real compile, so the fill warms each context
 * with one {@code --no-output} compile first.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerTypeCatalogService {

  /** One compiler-known type with no source: its dot path doubles as the FQN. */
  public record GeneratedType(@NotNull String contextKey, @NotNull String fqn, @NotNull String name) {
  }

  private record ContextCatalog(@NotNull HaxeCompilerDisplayService.DisplayContext context,
                                @NotNull Map<String, GeneratedType> byFqn,
                                @NotNull Map<String, List<GeneratedType>> byName,
                                @NotNull Map<String, String> moduleSigns) {
  }

  private static final long FAILURE_COOLDOWN_MS = 60_000;

  private final Project project;
  private final Map<String, ContextCatalog> catalogs = new ConcurrentHashMap<>();
  private final Set<String> filling = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
  /** Guards against a fill storm while the catalog legitimately stays empty (no contexts, server off). */
  private volatile long lastFillScheduledAt;

  public HaxeCompilerTypeCatalogService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerTypeCatalogService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerTypeCatalogService.class);
  }

  // --- queries (cache-only, safe under the read lock) ---

  @NotNull
  public List<GeneratedType> byName(@NotNull String name) {
    if (!enabled()) return List.of();
    List<GeneratedType> result = new ArrayList<>();
    for (ContextCatalog catalog : catalogs.values()) {
      result.addAll(catalog.byName().getOrDefault(name, List.of()));
    }
    return result;
  }

  @NotNull
  public List<GeneratedType> byFqn(@NotNull String fqn) {
    if (!enabled()) return List.of();
    List<GeneratedType> result = new ArrayList<>();
    for (ContextCatalog catalog : catalogs.values()) {
      GeneratedType entry = catalog.byFqn().get(fqn);
      if (entry != null) result.add(entry);
    }
    return result;
  }

  @NotNull
  public Set<String> allNames() {
    if (!enabled()) return Set.of();
    Set<String> result = new HashSet<>();
    for (ContextCatalog catalog : catalogs.values()) {
      result.addAll(catalog.byName().keySet());
    }
    return result;
  }

  /**
   * The entry's blueprint-rendered class. Cache-only like everything else on
   * this path: a cold blueprint schedules hydration and answers null this
   * once. Call in a read action.
   */
  @Nullable
  public HaxeClassModel materialize(@NotNull GeneratedType entry) {
    ContextCatalog catalog = catalogs.get(entry.contextKey());
    if (catalog == null) return null;
    return HaxeCompilerResolveService.getInstance(project).blueprintClass(catalog.context(), entry.fqn());
  }

  /** Answers whether the compiler leg should respond at all; a cold catalog schedules its fill. */
  private boolean enabled() {
    if (!HaxeCompilerSettings.getInstance(project).getCompletionMode().usesCompiler()) return false;
    if (catalogs.isEmpty()) scheduleFillAll();
    return true;
  }

  public void clearCaches() {
    catalogs.clear();
    failedAt.clear();
    lastFillScheduledAt = 0;
  }

  /**
   * Synchronous fill for the gated live-integration tests (production fills
   * stay background-scheduled and never run in unit-test mode). Performs the
   * warm-up compile and server round-trips on the calling thread.
   */
  @TestOnly
  public void fillNowForTests() {
    clearCaches();
    for (Map.Entry<String, HaxeCompilerDisplayService.DisplayContext> entry : moduleContexts().entrySet()) {
      fillContext(entry.getKey(), entry.getValue());
    }
  }

  /** Synchronous materialization for the gated live-integration tests: hydrates the blueprint, then renders. */
  @TestOnly
  @Nullable
  public HaxeClassModel materializeNowForTests(@NotNull GeneratedType entry) {
    ContextCatalog catalog = catalogs.get(entry.contextKey());
    if (catalog == null) return null;
    HaxeCompilerResolveService.getInstance(project).hydrateNowForTests(catalog.context(), entry.fqn());
    return materialize(entry);
  }

  // --- background fill ---

  private void scheduleFillAll() {
    // fixture tests have no compilation server to fill from, and the fill's
    // background index queries only add storage contention to the test run
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    if (System.currentTimeMillis() - lastFillScheduledAt < FAILURE_COOLDOWN_MS) return;
    if (!filling.add("*")) return;
    lastFillScheduledAt = System.currentTimeMillis();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        for (Map.Entry<String, HaxeCompilerDisplayService.DisplayContext> entry : moduleContexts().entrySet()) {
          fillContext(entry.getKey(), entry.getValue());
        }
      } catch (Throwable t) {
        log.warn("type catalog fill failed: " + t.getMessage());
      } finally {
        filling.remove("*");
      }
    });
  }

  /** Every module's display context, one per context key. */
  @NotNull
  private Map<String, HaxeCompilerDisplayService.DisplayContext> moduleContexts() {
    return ReadAction.computeBlocking(() -> {
      Map<String, HaxeCompilerDisplayService.DisplayContext> contexts = new LinkedHashMap<>();
      HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
      for (Module module : ModuleManager.getInstance(project).getModules()) {
        HaxeCompilerDisplayService.DisplayContext context = displayService.contextFor(module);
        if (context != null) {
          contexts.putIfAbsent(HaxeCompilerDisplayService.contextKey(context), context);
        }
      }
      return contexts;
    });
  }

  private void fillContext(@NotNull String contextKey, @NotNull HaxeCompilerDisplayService.DisplayContext context) {
    Long failed = failedAt.get(contextKey);
    if (failed != null && System.currentTimeMillis() - failed < FAILURE_COOLDOWN_MS) return;

    HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
    HaxeCompilerDisplayService.Connected connected = displayService.connectFor(context, DisplayMethods.SERVER_MODULES);
    if (connected == null) {
      failedAt.put(contextKey, System.currentTimeMillis());
      return;
    }
    // best effort: a failed warm-up must not abort the fill - the module
    // cache may already be warm from an earlier compile (and a re-run of
    // init macros can even fail on redefinitions while the cache is fine)
    displayService.ensureContextCompiled(connected, contextKey);

    try {
      ContextCatalog previous = catalogs.get(contextKey);
      ContextCatalog fresh = collectCatalog(contextKey, context, connected, previous);
      if (fresh != null) {
        catalogs.put(contextKey, fresh);
        failedAt.remove(contextKey);
      }
    } catch (DisplayRequestException e) {
      log.info("type catalog fill failed for context " + contextKey + ": " + e.getMessage());
      failedAt.put(contextKey, System.currentTimeMillis());
    }
  }

  /** Null when indexing is in progress — a dumb-mode diff would misread source types as generated. */
  @Nullable
  private ContextCatalog collectCatalog(@NotNull String contextKey,
                                        @NotNull HaxeCompilerDisplayService.DisplayContext context,
                                        @NotNull HaxeCompilerDisplayService.Connected connected,
                                        @Nullable ContextCatalog previous) throws DisplayRequestException {
    Map<String, GeneratedType> byFqn = new HashMap<>();
    Map<String, String> moduleSigns = new HashMap<>();

    for (String signature : typedContextSignatures(connected)) {
      List<String> listedModules = connected.client().modules(connected.args(), signature);
      Set<String> listed = new HashSet<>(listedModules);
      Set<String> dependencyOnly = new TreeSet<>();

      for (String modulePath : listedModules) {
        ModuleInfo info = moduleInfo(connected, signature, modulePath);
        if (info == null) continue;
        moduleSigns.put(modulePath, info.sign());

        // Context.defineType modules never appear in the listing (and
        // server/module rejects them); they only surface in the dependency
        // lists of the modules USING them - pinned by
        // LiveDisplayServerTest.macroDefinedTypeAppearsInModulesAndBlueprintsAfterACompile
        for (String dependency : info.dependencies()) {
          if (!listed.contains(dependency)) {
            dependencyOnly.add(dependency);
          }
        }

        boolean unchanged = previous != null && info.sign().equals(previous.moduleSigns().get(modulePath));
        if (unchanged) {
          copyEntries(previous, info.types(), byFqn);
          continue;
        }
        List<String> generated = sourcelessTypes(info.types());
        if (generated == null) return null;
        for (String fqn : generated) {
          byFqn.put(fqn, new GeneratedType(contextKey, fqn, simpleNameOf(fqn)));
        }
      }

      // A dependency-only module IS its single type (the defineType shape) -
      // there is no ModuleInfo to enumerate more from. Real source modules in
      // the set (std, haxelibs) fall out of the index diff.
      // TODO: Context.defineModule can create multi-type modules; whether the
      //  server exposes their type lists anywhere is unverified - only the
      //  module-named type is catalogued.
      List<String> generatedDependencies = sourcelessTypes(new ArrayList<>(dependencyOnly));
      if (generatedDependencies == null) return null;
      for (String fqn : generatedDependencies) {
        byFqn.put(fqn, new GeneratedType(contextKey, fqn, simpleNameOf(fqn)));
      }
    }

    Map<String, List<GeneratedType>> byName = new HashMap<>();
    for (GeneratedType entry : byFqn.values()) {
      byName.computeIfAbsent(entry.name(), k -> new ArrayList<>()).add(entry);
    }
    return new ContextCatalog(context, Map.copyOf(byFqn), Map.copyOf(byName), Map.copyOf(moduleSigns));
  }

  /**
   * The signatures of the server contexts holding TYPED modules — the
   * {@code after_init_macros} contexts; the macro context's modules are
   * macro-only and must not surface in completion.
   */
  @NotNull
  private static List<String> typedContextSignatures(@NotNull HaxeCompilerDisplayService.Connected connected)
    throws DisplayRequestException {
    List<String> signatures = new ArrayList<>();
    for (HaxeServerContext serverContext : connected.client().contexts(connected.args())) {
      if ("after_init_macros".equals(serverContext.desc())) {
        signatures.add(serverContext.signature());
      }
    }
    return signatures;
  }

  @Nullable
  private static ModuleInfo moduleInfo(@NotNull HaxeCompilerDisplayService.Connected connected,
                                       @NotNull String signature,
                                       @NotNull String modulePath) {
    try {
      return connected.client().module(connected.args(), signature, modulePath);
    } catch (DisplayRequestException e) {
      // a module that vanished between the listing and the detail request
      return null;
    }
  }

  /** Reuses the previous fill's generated-or-not verdicts for an unchanged module's types. */
  private static void copyEntries(@NotNull ContextCatalog previous,
                                  @NotNull List<String> typeFqns,
                                  @NotNull Map<String, GeneratedType> byFqn) {
    for (String fqn : typeFqns) {
      GeneratedType entry = previous.byFqn().get(fqn);
      if (entry != null) {
        byFqn.put(fqn, entry);
      }
    }
  }

  /**
   * The subset of a module's types that no source index knows — the generated
   * ones. Null while indexing is in progress (the diff would be wrong).
   */
  @Nullable
  private List<String> sourcelessTypes(@NotNull List<String> typeFqns) {
    return ReadAction.computeBlocking(() -> {
      if (DumbService.isDumb(project)) return null;
      GlobalSearchScope scope = GlobalSearchScope.allScope(project);
      List<String> generated = new ArrayList<>();
      for (String fqn : typeFqns) {
        // the two SOURCE legs only - asking the unified facade would recurse
        // into this catalog
        boolean inSource = !HaxeFullyQualifiedClassNameStubIndex.getByFqn(fqn, project, scope).isEmpty()
                           || !HaxeFullyQualifiedClassNameIndex.getByFqn(fqn, project, scope).isEmpty();
        if (!inSource) {
          generated.add(fqn);
        }
      }
      return generated;
    });
  }

  @NotNull
  private static String simpleNameOf(@NotNull String fqn) {
    return fqn.substring(fqn.lastIndexOf('.') + 1);
  }
}
