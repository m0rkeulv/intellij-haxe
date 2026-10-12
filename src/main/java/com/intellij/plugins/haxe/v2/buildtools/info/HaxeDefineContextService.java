package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.util.HaxeModuleVariants;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeDefine;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.context.HaxeResolveContext;
import com.intellij.plugins.haxe.v2.buildtools.context.HaxeResolveContexts;
import com.intellij.plugins.haxe.v2.buildtools.libraries.HaxeLibraryDefinesStore;
import com.intellij.plugins.haxe.v2.buildtools.settings.*;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.text.SemVer;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.intellij.plugins.haxe.lang.util.HaxeConditionalExpression.FLAG_DEFINE_VALUE;

/**
 * The define sets conditional compilation is evaluated with, one per build
 * file, derived from the v2 build configuration: the build file's own
 * defines (for Lime/OpenFL/NME files from the tool's evaluation, so
 * conditions are evaluated and toolchain defines included), the target's
 * platform defines and the library defines haxelib reports, overlaid with
 * the container's Define overrides. A source file is lexed with the set of
 * the build it is analysed under ({@link #definesForSource}); the active
 * build file's set ({@link #getActiveDefines}) answers everything asked
 * without a file, through {@code HaxeDefineDetectionManager.getAllDefinitions()}.
 * When a set changes, the files lexed with it are re-lexed
 * ({@link HaxeDefineContextInvalidator}).
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeDefineContextService implements Disposable, HaxeBuildSettingsListener {

  /**
   * Sentinel for {@link #lexedActive}: no file has been lexed under the
   * active context yet, so no reparse is owed. Identity-compared, never
   * handed out.
   */
  private static final Map<String, String> NEVER_LEXED = new LinkedHashMap<>();

  /** The defines that identify the compiler itself; no compiler lets a build toggle them. */
  private static final Set<String> COMPILER_IDENTITY_DEFINES = Set.of("haxe_ver", "haxe", "haxe3", "haxe4", "haxe5");

  /** Every input of a computed context; a mismatch invalidates the cached one. */
  private record ContextKey(@NotNull String path, @NotNull HaxeBuildFileType type, long contentStamp,
                            @Nullable String targetId, @Nullable String sdkName, @Nullable String customTarget,
                            @NotNull String compilerVersion, @NotNull List<EnvironmentDefine> overrides,
                            @NotNull Map<String, Map<String, String>> libraryDefines) {
  }

  private record Snapshot(@NotNull ContextKey key, @NotNull Map<String, String> defines) {
  }

  /**
   * Lock-free identity of every input reachable without VFS or read-action
   * work: the build file's content stamp. A match short-circuits the whole
   * computation - this runs per candidate inside index lookups and per lexer
   * reset, so the fast path must not touch findFileByPath or take a read
   * action.
   */
  private record FastState(@NotNull VirtualFile file, long contentStamp, @NotNull Map<String, String> defines) {
  }

  private final Project project;
  // both by build file path
  private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
  private final Map<String, FastState> fastStates = new ConcurrentHashMap<>();
  /**
   * What files were last LEXED under, as opposed to merely read: the active
   * build's defines for files without a resolve context, and per build file
   * path the defines of the files lexed under it. Only the lexer records
   * here. Annotators and completion read the same defines and must not,
   * or a daemon pass between a build-file change and the refresh would make
   * the new defines look already lexed under and no file would be reparsed.
   */
  private volatile Map<String, String> lexedActive = NEVER_LEXED;
  private final Map<String, Map<String, String>> lexedByPath = new ConcurrentHashMap<>();
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private final Object refreshLock = new Object();

  // the implicit single-file fallback behind HaxeKnownBuildFiles sweeps every
  // module under the read lock - far too heavy for getActiveDefines, which
  // runs per candidate inside index lookups. Resolved once per build-settings
  // change instead of per call (a module set change without a settings event
  // keeps the stale answer until the next one).
  private volatile String effectiveActivePath;
  private volatile boolean effectiveActivePathComputed;
  private volatile String activeContainerId;
  private volatile boolean activeContainerIdComputed;

  public HaxeDefineContextService(@NotNull Project project) {
    this.project = project;
    // config changes (language level, compiler settings) feed haxe_ver, so they invalidate the context too
    MessageBusConnection connection = project.getMessageBus().connect(this);
    connection.subscribe(HaxeBuildSettingsListener.TOPIC, this);
    connection.subscribe(HaxeBuildConfigListener.TOPIC, (HaxeBuildConfigListener)this::buildSettingsChanged);
  }

  public static HaxeDefineContextService getInstance(@NotNull Project project) {
    return project.getService(HaxeDefineContextService.class);
  }

  /**
   * Drops the derived context and recomputes in the background — a settings
   * mutation must reach parsing (re-index) and highlighting WITHOUT relying on
   * the tool window being open to call {@link #refreshAsync()}.
   */
  @Override
  public void buildSettingsChanged() {
    snapshots.clear();
    fastStates.clear();
    effectiveActivePathComputed = false;
    activeContainerIdComputed = false;
    refreshAsync();
  }

  /**
   * The current define context, or null when the project has no v2 active build
   * file (legacy detection applies then). Cached; recomputed when the build
   * file, target selection or environment overrides change.
   */
  @Nullable
  public Map<String, String> getActiveDefines() {
    String path = effectiveActivePath();
    if (StringUtil.isEmptyOrSpaces(path)) return null;

    FastState fast = fastStates.get(path);
    if (isFresh(fast)) {
      return fast.defines();
    }
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    return file != null && file.isValid() ? definesFor(file) : null;
  }

  /**
   * {@link #getActiveDefines} for the lexer: the result is recorded as what
   * files without a resolve context are lexed under, a null included (they
   * then use the legacy context, and activating a build file later must
   * reparse them).
   */
  @Nullable
  public Map<String, String> activeDefinesForLexing() {
    Map<String, String> result = getActiveDefines();
    lexedActive = result;
    return result;
  }

  /**
   * The defines a source file is compiled with: those of its resolve
   * context's build file; null for a file no build reaches (the project-wide
   * set applies then). A map lookup after the first ask.
   */
  @Nullable
  public Map<String, String> definesForSource(@NotNull VirtualFile sourceFile) {
    VirtualFile buildFile = contextBuildFile(sourceFile);
    return buildFile == null ? null : definesFor(buildFile);
  }

  /** {@link #definesForSource} for the lexer: the result is recorded as what the file is lexed under. */
  @Nullable
  public Map<String, String> definesForLexing(@NotNull VirtualFile sourceFile) {
    VirtualFile buildFile = contextBuildFile(sourceFile);
    if (buildFile == null) return null;
    Map<String, String> result = definesFor(buildFile);
    if (result != null) lexedByPath.put(buildFile.getPath(), result);
    return result;
  }

  @Nullable
  private VirtualFile contextBuildFile(@NotNull VirtualFile sourceFile) {
    HaxeResolveContext context = HaxeReadActions.compute(() -> HaxeResolveContexts.getInstance(project).contextFor(sourceFile));
    return context == null ? null : context.buildFile().file();
  }

  /** The build file's defines, null when the file is not a build file. */
  @Nullable
  public Map<String, String> definesFor(@NotNull VirtualFile buildFile) {
    String path = buildFile.getPath();
    FastState fast = fastStates.get(path);
    if (isFresh(fast)) {
      return fast.defines();
    }
    Map<String, String> result = HaxeReadActions.compute(() -> cachedOrComputedDefines(buildFile));
    if (result != null) {
      fastStates.put(path, new FastState(buildFile, contentStamp(buildFile), result));
    }
    return result;
  }

  private static boolean isFresh(@Nullable FastState fast) {
    return fast != null && fast.file().isValid() && contentStamp(fast.file()) == fast.contentStamp();
  }

  /** The build file whose defines a source file is lexed with: its resolve context's, else the active build file; null without either. */
  @Nullable
  public VirtualFile definingBuildFile(@NotNull VirtualFile sourceFile) {
    HaxeResolveContext context = HaxeReadActions.compute(() -> HaxeResolveContexts.getInstance(project).contextFor(sourceFile));
    return context != null ? context.buildFile().file() : activeBuildFile();
  }

  /** The physical file a PSI file's context derives from: a copy (completion) counts as its original; null for a light file. */
  @Nullable
  public static VirtualFile physicalFileOf(@Nullable PsiFile file) {
    VirtualFile virtualFile = file == null ? null : file.getOriginalFile().getVirtualFile();
    return virtualFile instanceof LightVirtualFile ? null : virtualFile;
  }

  /** A source file's resolve context changed (pin, unpin, navigation): that file alone is re-lexed under its new context. */
  public void fileContextChanged(@NotNull VirtualFile sourceFile) {
    HaxeDefineContextInvalidator.invalidateFile(project, sourceFile);
  }

  /**
   * Recomputes the context in the background and, when it actually changed,
   * has {@link HaxeDefineContextInvalidator} re-index the conditional haxe
   * files. Cheap when nothing changed — safe to call from every tree
   * refresh; requests arriving while one is queued coalesce into it.
   */
  public void refreshAsync() {
    if (!refreshQueued.compareAndSet(false, true)) return;
    AppExecutorUtil.getAppExecutorService().execute(this::refreshNow);
  }

  /**
   * Whether the build file's context defines the name before environment
   * overrides apply — the define quickfix uses this to decide between merely
   * dropping its own override and masking a build-file define with a REMOVE
   * entry.
   */
  public boolean isDefinedWithoutOverrides(@NotNull VirtualFile buildFile, @NotNull String name) {
    return HaxeReadActions.compute(() -> {
      HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, buildFile);
      if (type == null) return false;
      return definesBeforeOverrides(buildFile, type).containsKey(HaxeDefine.compilerName(name));
    });
  }

  /** Whether the define identifies the compiler ({@code haxe_ver}, {@code haxe}, {@code haxeN}); an override cannot sensibly toggle it. */
  public static boolean isCompilerIdentityDefine(@NotNull String name) {
    return COMPILER_IDENTITY_DEFINES.contains(name);
  }

  /**
   * The container owning the ACTIVE build file, or null without one — what
   * the container library/SDK elements resolve their language level
   * against. Cached (the language level lookup calls this per annotated
   * element); callers hold the read lock.
   */
  @Nullable
  public String activeContainerId() {
    if (!activeContainerIdComputed) {
      VirtualFile file = activeBuildFile();
      activeContainerId = file == null ? null : HaxeContainers.containerIdFor(project, file);
      activeContainerIdComputed = true;
    }
    return activeContainerId;
  }

  @Nullable
  private String effectiveActivePath() {
    if (!effectiveActivePathComputed) {
      effectiveActivePath = HaxeReadActions.compute(() -> HaxeKnownBuildFiles.effectiveActivePath(project));
      effectiveActivePathComputed = true;
    }
    return effectiveActivePath;
  }

  @Nullable
  private VirtualFile activeBuildFile() {
    String path = effectiveActivePath();
    if (StringUtil.isEmptyOrSpaces(path)) return null;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    return file != null && file.isValid() ? file : null;
  }

  private static long contentStamp(@NotNull VirtualFile file) {
    Document document = FileDocumentManager.getInstance().getCachedDocument(file);
    return document != null ? document.getModificationStamp() : file.getModificationStamp();
  }

  private void refreshNow() {
    if (project.isDisposed()) return;
    synchronized (refreshLock) {
      // cleared under the lock: requests arriving during this refresh fold into ONE follow-up
      refreshQueued.set(false);
      // compare against the defines files were last LEXED under, NOT the
      // snapshot cache: the invalidation topic clears the snapshot synchronously
      // before this runs. The sentinel keeps project open cheap (nothing was
      // lexed, nothing to re-index) while a recorded null still pushes on the
      // null -> non-null transition (build file activated after files were
      // parsed against the legacy context).
      Map<String, String> before = lexedActive;
      Map<String, Map<String, String>> byPathBefore = Map.copyOf(lexedByPath);
      snapshots.clear();
      fastStates.clear();
      // one non-blocking read action around the recompute: this is a pooled
      // thread, and the inner blocking read forms then nest instead of freezing the UI
      Map<String, String> after = ReadAction.nonBlocking(this::getActiveDefines)
        .expireWith(this)
        .executeSynchronously();
      lexedActive = after;
      Set<String> changed = changedContexts(before, after, byPathBefore);
      if (log.isDebugEnabled()) {
        log.debug("define context refresh: changed=" + (changed == null ? "all" : changed.size()) + ", defines=" + (after == null ? "legacy" : after.size()));
      }
      if (changed == null) {
        HaxeDefineContextInvalidator.invalidateConditionalFiles(project);
      }
      else if (!changed.isEmpty()) {
        HaxeDefineContextInvalidator.invalidateConditionalFilesOf(project, changed);
      }
    }
  }

  /**
   * The build files whose defines differ from what files were lexed under:
   * the active one when its set changed (the files following the active
   * build are lexed under it), and every lexed-under build file whose set
   * changed. Null when every conditional file must be re-lexed: the active
   * context went away, or a lexed-under build file is gone and its files now
   * fall to other contexts.
   */
  @Nullable
  private Set<String> changedContexts(@Nullable Map<String, String> activeBefore,
                                      @Nullable Map<String, String> activeAfter,
                                      @NotNull Map<String, Map<String, String>> byPathBefore) {
    Set<String> changed = new HashSet<>();
    boolean activeChanged = activeBefore != NEVER_LEXED && !Objects.equals(activeBefore, activeAfter);
    boolean everything = false;
    if (activeChanged) {
      String activePath = effectiveActivePath();
      if (activePath == null) everything = true;
      else changed.add(activePath);
    }
    for (Map.Entry<String, Map<String, String>> entry : byPathBefore.entrySet()) {
      Map<String, String> after = recomputedDefines(entry.getKey());
      if (after == null) {
        lexedByPath.remove(entry.getKey());
        everything = true;
      }
      else if (!Objects.equals(entry.getValue(), after)) {
        changed.add(entry.getKey());
      }
    }
    return everything ? null : changed;
  }

  @Nullable
  private Map<String, String> recomputedDefines(@NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || !file.isValid()) return null;
    return ReadAction.nonBlocking(() -> definesFor(file))
      .expireWith(this)
      .executeSynchronously();
  }

  /** The snapshot's defines while its inputs are unchanged, else a fresh computation. Call in a read action. */
  @Nullable
  private Map<String, String> cachedOrComputedDefines(@NotNull VirtualFile file) {
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type == null) return null;

    ContextKey key = contextKey(file, type);
    Snapshot current = snapshots.get(key.path());
    if (current != null && current.key().equals(key)) {
      return current.defines();
    }
    Map<String, String> defines = compute(file, type);
    snapshots.put(key.path(), new Snapshot(key, defines));
    return defines;
  }

  @NotNull
  private ContextKey contextKey(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    String containerId = HaxeContainers.containerIdFor(project, file);
    HaxeEnvironmentStore environment = HaxeEnvironmentStore.getInstance(project);
    String targetId = HaxeTargetSelectionStore.getInstance(project).getSelectedTargetId(file);
    String sdkName = environment.getSdkName(containerId);
    String customTarget = environment.getCustomTarget(containerId);
    String compilerVersion = HaxeLanguageLevelUtil.getHaxeVersion(project, containerId);
    List<EnvironmentDefine> overrides = environment.getDefines(containerId);
    Map<String, Map<String, String>> libraryDefines = HaxeLibraryDefinesStore.getInstance(project).definesByDependency(containerId);
    return new ContextKey(file.getPath(), type, contentStamp(file), targetId, sdkName, customTarget, compilerVersion,
                          overrides, libraryDefines);
  }

  /** Every define name passes through {@link HaxeDefine#compilerName}: the compiler's key is what {@code #if} looks up. */
  @NotNull
  private Map<String, String> compute(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    Map<String, String> defines = definesBeforeOverrides(file, type);

    String containerId = HaxeContainers.containerIdFor(project, file);
    for (EnvironmentDefine override : HaxeEnvironmentStore.getInstance(project).getDefines(containerId)) {
      String name = HaxeDefine.compilerName(override.name());
      if (override.effect() == DefineEffect.REMOVE) {
        defines.remove(name);
      } else {
        defines.put(name, override.value().isEmpty() ? FLAG_DEFINE_VALUE : override.value());
      }
    }
    return defines;
  }

  /** The context's defines BEFORE the container's environment overrides apply - everything an override can mask. */
  @NotNull
  private Map<String, String> definesBeforeOverrides(@NotNull VirtualFile file, @NotNull HaxeBuildFileType type) {
    HaxeBuildFileInfo info = effectiveInfo(new HaxeBuildFile(file, type));
    String containerId = HaxeContainers.containerIdFor(project, file);

    Map<String, String> defines = new LinkedHashMap<>();
    // the compiler implicitly defines the target (hl, js, sys, ...) before
    // reading the build file - the std library's per-target sources are gated
    // on exactly these
    if (info.target() != null) {
      defines.putAll(info.target().getDefines());
    }
    for (HaxeDefine define : info.defines()) {
      String value = define.value() != null ? define.value() : FLAG_DEFINE_VALUE;
      defines.put(HaxeDefine.compilerName(define.name()), value);
    }
    defines.putAll(libraryDefines(info, containerId));
    putCompilerIdentityDefines(defines, containerId);
    putHashlinkVersionDefine(defines, containerId);
    putCustomTargetDefines(defines, containerId);
    return defines;
  }

  /**
   * The {@code -D lib=version} defines the compiler receives for the build's
   * libraries and their transitives, as the last library sync recorded them
   * from {@code haxelib path}; empty before the first sync. Keyed by the
   * compiler's spelling.
   */
  @NotNull
  private Map<String, String> libraryDefines(@NotNull HaxeBuildFileInfo info, @NotNull String containerId) {
    Map<String, Map<String, String>> byDependency = HaxeLibraryDefinesStore.getInstance(project).definesByDependency(containerId);
    Map<String, String> defines = new LinkedHashMap<>();
    for (HaxeLibDependency library : info.libraries()) {
      byDependency.getOrDefault(library.name(), Map.of())
        .forEach((name, value) -> defines.put(HaxeDefine.compilerName(name), value));
    }
    return defines;
  }

  /**
   * The compiler's identity defines ({@code haxe_ver}, {@code haxe} and the
   * major-version flags), mirrored so `#if (haxe_ver >= 4.1)` blocks activate
   * correctly. Sourced from the language level or the container's compiler
   * version per the compiler settings; wins over a build-tool-reported value
   * (the point of choosing the level), while the container's explicit Define
   * overrides still apply on top.
   */
  private void putCompilerIdentityDefines(@NotNull Map<String, String> defines, @NotNull String containerId) {
    String version = HaxeLanguageLevelUtil.getHaxeVersion(project, containerId);
    defines.put("haxe_ver", version);
    defines.put("haxe", version);
    SemVer semVer = SemVer.parseFromText(version);
    if (semVer != null) {
      if (semVer.getMajor() >= 3) defines.put("haxe3", version);
      if (semVer.getMajor() >= 4) defines.put("haxe4", version);
      if (semVer.getMajor() >= 5) defines.put("haxe5", version);
    }
  }

  /**
   * The container's Custom target setting, mirrored as the defines
   * {@code --custom-target} sets: {@code custom_target} and
   * {@code target.name=<name>} (never the bare name — a custom target does
   * NOT define itself the way stock targets do). Wins over an hxml-declared
   * custom target, which arrives through the build file's defines; the
   * container's explicit Define overrides still apply on top.
   */
  private void putCustomTargetDefines(@NotNull Map<String, String> defines, @NotNull String containerId) {
    String customTarget = HaxeEnvironmentStore.getInstance(project).getCustomTarget(containerId);
    if (customTarget == null) return;
    defines.put(HaxeModuleVariants.CUSTOM_TARGET_DEFINE, FLAG_DEFINE_VALUE);
    defines.put(HaxeModuleVariants.TARGET_NAME_DEFINE, customTarget);
  }

  /**
   * The compiler defaults {@code hl_ver} by reading {@code std/hl/hl_version}
   * from its standard library when the define is absent; mirror that so
   * {@code #if (hl_ver >= version("1.12.0"))} blocks activate correctly out
   * of the box. Synthesized for EVERY target - the value is a static property
   * of the configured toolchain, target membership is what {@code #if hl}
   * answers, and a target-dependent define would flicker with the selection.
   * A build-file {@code -D hl-ver} keeps precedence (absence-guarded), and
   * the container's environment overrides still apply on top.
   */
  private void putHashlinkVersionDefine(@NotNull Map<String, String> defines, @NotNull String containerId) {
    if (defines.containsKey("hl_ver")) return;
    String version = readStdHlVersion(containerId);
    if (version != null) {
      defines.put("hl_ver", version);
    }
  }

  @Nullable
  private String readStdHlVersion(@NotNull String containerId) {
    Sdk sdk = HaxeToolPathResolver.resolveSdk(project, containerId);
    if (sdk == null) return null;
    for (VirtualFile root : sdk.getRootProvider().getFiles(OrderRootType.SOURCES)) {
      VirtualFile versionFile = root.findFileByRelativePath("hl/hl_version");
      if (versionFile == null) continue;
      try {
        String version = VfsUtilCore.loadText(versionFile).trim();
        return version.isEmpty() ? null : version;
      }
      catch (IOException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * The build file's parsed info; lime-family and nmml files use the selected
   * target's tool evaluation when available. Until the background run lands
   * (which re-triggers a refresh) — or without one: untrusted project, failed
   * tool — the raw xml defines serve with the selected target's platform
   * defines; {@code if}/{@code unless} conditions stay unevaluated then.
   */
  @NotNull
  private HaxeBuildFileInfo effectiveInfo(@NotNull HaxeBuildFile buildFile) {
    HaxeBuildFileInfo raw = HaxeBuildSections.inspectSelected(project, buildFile);
    HaxeBuildFileType type = buildFile.type();
    String containerId = HaxeContainers.containerIdFor(project, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    if (type == HaxeBuildFileType.NMML) {
      return HaxeNmeProjectInfoService.getInstance(project).effectiveInfo(buildFile, raw, environmentSdk, this::refreshAsync);
    }
    if (!LimeProjects.isLimeFamily(type)) return raw;

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    HaxeBuildFileInfo display = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, this::refreshAsync);
    return display != null ? display : raw.withTarget(LimeProjects.targetFor(targetFlag), null);
  }

  @Override
  public void dispose() {
  }
}
