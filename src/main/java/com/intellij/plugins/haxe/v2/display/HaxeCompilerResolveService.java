package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.JsonTypeRef;
import com.intellij.plugins.haxe.display.protocol.server.HaxeServerContext;
import com.intellij.plugins.haxe.display.protocol.server.TypeBlueprint;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeFile;
import com.intellij.plugins.haxe.lang.psi.HaxeModule;
import com.intellij.plugins.haxe.lang.psi.HaxeReference;
import com.intellij.plugins.haxe.lang.psi.HaxeReferenceExpression;
import com.intellij.plugins.haxe.lang.psi.HaxeResolveResult;
import com.intellij.plugins.haxe.model.HaxeBaseMemberModel;
import com.intellij.plugins.haxe.model.HaxeClassModel;
import com.intellij.plugins.haxe.model.HaxeModuleModel;
import com.intellij.plugins.haxe.util.HaxeElementGenerator;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * The compiler-backed last resort of {@code HaxeResolver}: when static
 * resolution fails, an UNQUALIFIED reference is looked up in the enclosing
 * class's post-macro blueprint ({@code server/type}), where macro-generated
 * members exist with their real types.
 *
 * Resolve runs under the read lock, so this path is strictly cache-only: a
 * miss schedules background hydration and fails this once; the daemon restart
 * after hydration re-resolves from the cache. Resolved members materialize as
 * a synthetic field declaration carrying the blueprint type — real PSI, so
 * type inference, completion and chained member access downstream all work
 * off it (a generated {@code button1:haxe.ui.components.Button} makes
 * {@code button1.text} resolve statically against the real Button class).
 *
 * Gated on the completion mode (Settings | Compiler | Haxe Compiler): in
 * "IDE only" this service answers nothing; the compiler-diagnostics toggle
 * only governs problem highlighting.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerResolveService {

  private record BlueprintKey(@NotNull String contextKey, @NotNull String dotPath) {
  }

  private static final long FAILURE_COOLDOWN_MS = 30_000;
  /**
   * Depth-one bound: resolving the RECEIVER inside the fallback re-runs the
   * resolver, whose own failures re-enter this fallback. The resolver cannot
   * negatively cache failures (a wrong path's cached failure would mask a
   * success reachable through another path), so failed sub-chains recompute
   * on every visit - nested fallbacks multiply that cost without bound and
   * once froze a full resolve pass. Each reference gets the fallback when it
   * is the SUBJECT of resolution, never transitively inside another
   * reference's fallback.
   */
  private static final ThreadLocal<Boolean> inFallback = ThreadLocal.withInitial(() -> false);

  private final Project project;
  private final Map<BlueprintKey, TypeBlueprint> blueprints = new ConcurrentHashMap<>();
  private final Set<BlueprintKey> hydrating = ConcurrentHashMap.newKeySet();
  private final Map<BlueprintKey, Long> failedAt = new ConcurrentHashMap<>();
  private final Map<BlueprintKey, HaxeFile> blueprintFiles = new ConcurrentHashMap<>();

  public HaxeCompilerResolveService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerResolveService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerResolveService.class);
  }

  /**
   * The cache-only fallback. Null when this reference is out of scope, the
   * blueprint is not hydrated yet, or the compiler does not know the member
   * either. Never touches the network; safe under the read lock.
   *
   * Unqualified identifiers look up the enclosing class; qualified ones look
   * up the RECEIVER's statically-resolved class ({@code this.x},
   * {@code ClassName.x}, {@code view.x}) — the receiver itself usually
   * resolves statically even when the member is generated.
   */
  @Nullable
  public List<? extends PsiElement> tryResolve(@NotNull HaxeReference reference) {
    if (!(reference instanceof HaxeReferenceExpression expression)) return null;
    if (!HaxeCompilerSettings.getInstance(project).getCompletionMode().usesCompiler()) return null;
    if (DumbService.isDumb(project)) return null;
    String name = expression.getReferenceName();
    if (name == null || name.isEmpty()) return null;

    // context check BEFORE resolving the receiver: targetClassOf recurses into
    // resolve, and without a build context (fixture tests, non-v2 projects)
    // this path can never answer - the recursion would be pure overhead
    VirtualFile contextFile = fileOf(expression);
    if (contextFile == null) return null;
    if (HaxeCompilerDisplayService.getInstance(project).contextFor(contextFile) == null) return null;

    if (inFallback.get()) return null;
    inFallback.set(true);
    try {
      HaxeClass targetClass = targetClassOf(expression);
      if (targetClass == null) return null;
      BlueprintLookup lookup = blueprintLookup(contextFile, targetClass.getQualifiedName());
      if (lookup == null || lookup.blueprint().findMember(name) == null) return null;
      PsiElement member = blueprintMember(lookup.key(), lookup.blueprint(), name);
      return member != null ? List.of(member) : null;
    } finally {
      inFallback.set(false);
    }
  }

  /** The class whose blueprint can hold the referenced member. */
  @Nullable
  private static HaxeClass targetClassOf(@NotNull HaxeReferenceExpression expression) {
    if (expression.getFirstChild() instanceof HaxeReference receiver) {
      HaxeResolveResult receiverResult = receiver.resolveHaxeClass();
      return receiverResult != null ? receiverResult.getHaxeClass() : null;
    }
    return PsiTreeUtil.getParentOfType(expression, HaxeClass.class);
  }

  /**
   * The hydrated blueprint of a type, resolved through the EDITED file's
   * build context (so library receivers hydrate too). Cache-only; a miss
   * schedules hydration and returns null. Call in a read action.
   */
  @Nullable
  public TypeBlueprint blueprintForType(@NotNull VirtualFile contextFile, @Nullable String dotPath) {
    if (!HaxeCompilerSettings.getInstance(project).getCompletionMode().usesCompiler()) return null;
    if (DumbService.isDumb(project)) return null;
    BlueprintLookup lookup = blueprintLookup(contextFile, dotPath);
    return lookup != null ? lookup.blueprint() : null;
  }

  /**
   * The blueprint-rendered class of a compiler-known type, addressed through
   * an explicit display context — for the type catalog, whose entries carry
   * no source file. Cache-only; a miss schedules hydration and returns null.
   * Call in a read action.
   */
  @Nullable
  public HaxeClassModel blueprintClass(@NotNull HaxeCompilerDisplayService.DisplayContext context, @NotNull String dotPath) {
    if (!HaxeCompilerSettings.getInstance(project).getCompletionMode().usesCompiler()) return null;
    BlueprintKey key = new BlueprintKey(HaxeCompilerDisplayService.contextKey(context), dotPath);
    TypeBlueprint blueprint = blueprints.get(key);
    if (blueprint == null) {
      scheduleHydration(key, context);
      return null;
    }
    return renderedClassModel(key, blueprint);
  }

  private record BlueprintLookup(@NotNull BlueprintKey key, @NotNull TypeBlueprint blueprint) {
  }

  @Nullable
  private BlueprintLookup blueprintLookup(@NotNull VirtualFile contextFile, @Nullable String dotPath) {
    if (dotPath == null || dotPath.isEmpty()) return null;
    HaxeCompilerDisplayService.DisplayContext context = HaxeCompilerDisplayService.getInstance(project).contextFor(contextFile);
    if (context == null) return null;

    BlueprintKey key = new BlueprintKey(HaxeCompilerDisplayService.contextKey(context), dotPath);
    TypeBlueprint blueprint = blueprints.get(key);
    if (blueprint == null) {
      // hydration compiles the context - pointless while the edited file
      // does not even parse; cache hits above stay served regardless
      if (HaxeCompilerDisplayService.isSyntaxClean(project, contextFile)) {
        scheduleHydration(key, context);
      }
      return null;
    }
    return new BlueprintLookup(key, blueprint);
  }

  /** The physical file of an element; completion copies map back to the file on disk. */
  @Nullable
  private static VirtualFile fileOf(@NotNull PsiElement element) {
    PsiFile file = element.getContainingFile();
    return file != null ? file.getOriginalFile().getVirtualFile() : null;
  }

  // TODO: no automatic blueprint invalidation - an edited macro input (the
  //  haxeui XML, a lib's include.xml) only takes effect after Purge Caches
  //  or a restart.
  public void clearCaches() {
    blueprints.clear();
    blueprintFiles.clear();
    failedAt.clear();
  }

  // --- synthetic PSI ---

  /**
   * Members materialize from a virtual extern class rendered out of the
   * blueprint — real (non-physical) PSI with real type tags, the same
   * mechanism {@code HaxeSyntheticDeclarations} uses for {@code trace}: type
   * inference, completion and chained access all work off it, and navigation
   * lands on a readable declaration.
   */
  @Nullable
  private PsiElement blueprintMember(@NotNull BlueprintKey key, @NotNull TypeBlueprint blueprint, @NotNull String name) {
    HaxeClassModel renderedClass = renderedClassModel(key, blueprint);
    if (renderedClass == null) return null;
    HaxeBaseMemberModel member = renderedClass.getMember(name, null);
    return member != null ? member.getBasePsi() : null;
  }

  @Nullable
  private HaxeClassModel renderedClassModel(@NotNull BlueprintKey key, @NotNull TypeBlueprint blueprint) {
    HaxeFile file = blueprintFiles.computeIfAbsent(key, k -> buildBlueprintFile(k, blueprint));
    HaxeModule module = file.getModule();
    if (module == null) return null;
    HaxeModuleModel model = (HaxeModuleModel)module.getModel();
    return model.getClass(typeNameOf(key.dotPath()));
  }

  @NotNull
  private HaxeFile buildBlueprintFile(@NotNull BlueprintKey key, @NotNull TypeBlueprint blueprint) {
    String typeName = typeNameOf(key.dotPath());
    StringBuilder text = new StringBuilder();
    text.append("/**\n")
      .append("   This class does not exist as source. It is the compiler's\n")
      .append("   post-macro blueprint of `").append(key.dotPath()).append("` -\n")
      .append("   members generated by macros resolve here.\n")
      .append("**/\n")
      .append("extern class ").append(typeName).append(" {\n");
    for (TypeBlueprint.Member member : blueprint.fields()) {
      appendMember(text, member, false);
    }
    for (TypeBlueprint.Member member : blueprint.statics()) {
      appendMember(text, member, true);
    }
    text.append("}\n");
    return HaxeElementGenerator.createFile(project, typeName, text.toString());
  }

  private static void appendMember(@NotNull StringBuilder text, @NotNull TypeBlueprint.Member member, boolean isStatic) {
    text.append("\tpublic ");
    if (isStatic) text.append("static ");
    JsonTypeRef type = member.type();
    if (member.isMethod() && type != null && type.isFunction()) {
      text.append("function ").append(member.name())
        .append('(').append(parameterListText(type)).append("):")
        .append(safeTypeText(JsonTypeRef.of(type.args().path("ret"))));
    } else {
      text.append("var ").append(member.name()).append(':').append(safeTypeText(type));
    }
    text.append(";\n");
  }

  @NotNull
  private static String parameterListText(@NotNull JsonTypeRef functionType) {
    List<String> parameters = new ArrayList<>();
    int index = 0;
    for (var argument : functionType.args().path("args")) {
      String name = argument.path("name").asString("");
      // parameter names must be identifiers - the compiler can emit odd ones for closures
      if (!name.matches("[A-Za-z_]\\w*")) {
        name = "arg" + index;
      }
      boolean optional = argument.path("opt").asBoolean(false);
      parameters.add((optional ? "?" : "") + name + ":" + safeTypeText(JsonTypeRef.of(argument.path("t"))));
      index++;
    }
    return String.join(", ", parameters);
  }

  @NotNull
  private static String typeNameOf(@NotNull String dotPath) {
    return dotPath.substring(dotPath.lastIndexOf('.') + 1);
  }

  /**
   * Renders a blueprint type as haxe type syntax the parser accepts; anything
   * not safely expressible degrades to Dynamic rather than producing a
   * declaration that fails to parse.
   */
  @NotNull
  static String safeTypeText(@Nullable JsonTypeRef type) {
    if (type == null) return "Dynamic";
    return switch (type.kind()) {
      case "TInst", "TEnum", "TType", "TAbstract" -> classTypeText(type);
      case "TFun" -> functionTypeText(type);
      default -> "Dynamic";
    };
  }

  @NotNull
  private static String classTypeText(@NotNull JsonTypeRef type) {
    String dotPath = type.dotPath();
    // a plain dot path of identifiers - privates/natives can carry other shapes
    if (dotPath == null || !dotPath.matches("[A-Za-z_][\\w.]*")) return "Dynamic";
    List<String> parameters = new ArrayList<>();
    for (var param : type.args().path("params")) {
      parameters.add(safeTypeText(JsonTypeRef.of(param)));
    }
    return parameters.isEmpty() ? dotPath : dotPath + "<" + String.join(", ", parameters) + ">";
  }

  @NotNull
  private static String functionTypeText(@NotNull JsonTypeRef type) {
    String arguments = StreamSupport
      .stream(type.args().path("args").spliterator(), false)
      .map(argument -> safeTypeText(JsonTypeRef.of(argument.path("t"))))
      .collect(Collectors.joining(", "));
    String returnType = safeTypeText(JsonTypeRef.of(type.args().path("ret")));
    return "(" + arguments + ") -> " + returnType;
  }

  // --- hydration ---

  /**
   * Synchronous hydration for the gated live-integration tests (production
   * hydration stays background-scheduled and never runs in unit-test mode).
   */
  @TestOnly
  public void hydrateNowForTests(@NotNull HaxeCompilerDisplayService.DisplayContext context, @NotNull String dotPath) {
    BlueprintKey key = new BlueprintKey(HaxeCompilerDisplayService.contextKey(context), dotPath);
    TypeBlueprint blueprint = hydrate(key, context);
    if (blueprint != null) {
      blueprints.put(key, blueprint);
    }
  }

  private void scheduleHydration(@NotNull BlueprintKey key, @NotNull HaxeCompilerDisplayService.DisplayContext context) {
    // fixture tests have no server to hydrate from; never spawn the attempt
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    Long failed = failedAt.get(key);
    if (failed != null && System.currentTimeMillis() - failed < FAILURE_COOLDOWN_MS) return;
    if (!hydrating.add(key)) return;

    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        TypeBlueprint blueprint = hydrate(key, context);
        if (blueprint != null) {
          blueprints.put(key, blueprint);
          failedAt.remove(key);
          restartHighlighting();
        } else {
          failedAt.put(key, System.currentTimeMillis());
        }
      } catch (Throwable t) {
        log.warn("blueprint hydration failed for " + key.dotPath() + ": " + t.getMessage());
        failedAt.put(key, System.currentTimeMillis());
      } finally {
        hydrating.remove(key);
      }
    });
  }

  @Nullable
  private TypeBlueprint hydrate(@NotNull BlueprintKey key, @NotNull HaxeCompilerDisplayService.DisplayContext context) {
    HaxeCompilerDisplayService displayService = HaxeCompilerDisplayService.getInstance(project);
    HaxeCompilerDisplayService.Connected connected = displayService.connectFor(context, DisplayMethods.SERVER_TYPE);
    if (connected == null) return null;

    // best effort: a failed warm-up still tries the lookup - the module may
    // already sit in the server's cache from an earlier compile
    displayService.ensureContextCompiled(connected, key.contextKey());

    try {
      // the type's module path: for Module.SubType the module is the prefix
      String modulePath = key.dotPath();
      String typeName = modulePath.substring(modulePath.lastIndexOf('.') + 1);
      return blueprintFromAnyContext(connected, modulePath, typeName);
    } catch (DisplayRequestException e) {
      log.info("server/type failed for " + key.dotPath() + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * server/type against the context holding the module. A source module's
   * context is found through the module listing; a Context.defineType module
   * is LISTED nowhere (see the display-protocol README), so the typed
   * ({@code after_init_macros}) contexts are tried blind — server/type itself
   * is the test of whether the type lives there.
   */
  @Nullable
  private TypeBlueprint blueprintFromAnyContext(@NotNull HaxeCompilerDisplayService.Connected connected,
                                                @NotNull String modulePath,
                                                @NotNull String typeName) throws DisplayRequestException {
    List<String> candidates = new ArrayList<>();
    for (HaxeServerContext context : connected.client().contexts(connected.args())) {
      try {
        if (connected.client().modules(connected.args(), context.signature()).contains(modulePath)) {
          // listed = certain; try before the blind candidates
          candidates.add(0, context.signature());
          continue;
        }
      } catch (DisplayRequestException ignored) {
        // some contexts reject module listing - try the next
      }
      if ("after_init_macros".equals(context.desc())) {
        candidates.add(context.signature());
      }
    }
    for (String signature : candidates) {
      try {
        return connected.client().typeBlueprint(connected.args(), signature, modulePath, typeName);
      } catch (DisplayRequestException ignored) {
        // the type does not live in this context - try the next
      }
    }
    return null;
  }

  /**
   * A landed blueprint changes what DEPENDENT references resolve to:
   * {@code button1.text} was computed (and cached empty) while {@code button1}
   * was still unknown. The fallback only covers the root reference, so the
   * chain's stale results must go before the re-highlight — and not just the
   * resolve caches: type-evaluation CachedValues key on the PSI modification
   * count and survive dropResolveCaches, so the full dropPsiCaches it is
   * (rare enough here: once per hydrated class per session).
   */
  private void restartHighlighting() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!project.isDisposed()) {
        PsiManager.getInstance(project).dropPsiCaches();
        DaemonCodeAnalyzer.getInstance(project).restart("haxe: compiler resolve results hydrated");
      }
    });
  }
}
