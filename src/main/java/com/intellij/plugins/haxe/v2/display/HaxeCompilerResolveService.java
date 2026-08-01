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
import com.intellij.plugins.haxe.display.transport.DisplayResponse;
import com.intellij.plugins.haxe.display.transport.HaxeDisplayTransport;
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

  private final Project project;
  private final Map<BlueprintKey, TypeBlueprint> blueprints = new ConcurrentHashMap<>();
  private final Set<BlueprintKey> hydrating = ConcurrentHashMap.newKeySet();
  private final Map<BlueprintKey, Long> failedAt = new ConcurrentHashMap<>();
  /** Contexts already warmed with a compile this session (the module cache needs one). */
  private final Set<String> compiledContexts = ConcurrentHashMap.newKeySet();
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

    HaxeClass targetClass = targetClassOf(expression);
    if (targetClass == null) return null;
    VirtualFile contextFile = fileOf(expression);
    if (contextFile == null) return null;
    BlueprintLookup lookup = blueprintLookup(contextFile, targetClass.getQualifiedName());
    if (lookup == null || lookup.blueprint().findMember(name) == null) return null;
    PsiElement member = blueprintMember(lookup.key(), lookup.blueprint(), name);
    return member != null ? List.of(member) : null;
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
    compiledContexts.clear();
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
    HaxeFile file = blueprintFiles.computeIfAbsent(key, k -> buildBlueprintFile(k, blueprint));
    HaxeModule module = file.getModule();
    if (module == null) return null;
    HaxeModuleModel model = (HaxeModuleModel)module.getModel();
    HaxeClassModel renderedClass = model.getClass(typeNameOf(key.dotPath()));
    if (renderedClass == null) return null;
    HaxeBaseMemberModel member = renderedClass.getMember(name, null);
    return member != null ? member.getBasePsi() : null;
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

  private void scheduleHydration(@NotNull BlueprintKey key, @NotNull HaxeCompilerDisplayService.DisplayContext context) {
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
    HaxeCompilerDisplayService.Connected connected =
      HaxeCompilerDisplayService.getInstance(project).connectFor(context, DisplayMethods.SERVER_TYPE);
    if (connected == null) return null;

    // the server's module cache only fills from an actual compile - warm each
    // context once per session. A FAILED compile (broken code) must not count
    // as warmed, or hydration stays broken until a purge even after the code
    // is fixed; retry on the next attempt instead.
    if (!compiledContexts.contains(key.contextKey())) {
      List<String> compileArgs = new ArrayList<>(connected.args());
      compileArgs.add("--no-output");
      try {
        DisplayResponse compiled =
          HaxeDisplayTransport.request("127.0.0.1", connected.port(), compileArgs, 120_000);
        if (!compiled.hasError()) {
          compiledContexts.add(key.contextKey());
        } else {
          log.info("context warm-up compile reported errors - will retry: " + compiled.payload());
        }
      } catch (DisplayRequestException e) {
        log.info("context warm-up compile failed: " + e.getMessage());
      }
    }

    try {
      // the type's module path: for Module.SubType the module is the prefix
      String modulePath = key.dotPath();
      String typeName = modulePath.substring(modulePath.lastIndexOf('.') + 1);
      String signature = signatureFor(connected, modulePath);
      if (signature == null) return null;
      return connected.client().typeBlueprint(connected.args(), signature, modulePath, typeName);
    } catch (DisplayRequestException e) {
      log.info("server/type failed for " + key.dotPath() + ": " + e.getMessage());
      return null;
    }
  }

  /** The cache context that actually holds the module (the macro context does not). */
  @Nullable
  private String signatureFor(@NotNull HaxeCompilerDisplayService.Connected connected, @NotNull String modulePath)
    throws DisplayRequestException {
    List<HaxeServerContext> contexts = connected.client().contexts(connected.args());
    for (HaxeServerContext context : contexts) {
      try {
        if (connected.client().modules(connected.args(), context.signature()).contains(modulePath)) {
          return context.signature();
        }
      } catch (DisplayRequestException ignored) {
        // some contexts reject module listing - try the next
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
