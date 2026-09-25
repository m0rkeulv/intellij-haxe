package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.v2.buildtools.server.HaxeServerMetrics;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.buildtools.info.HaxeNmeProjectInfoService;
import com.intellij.plugins.haxe.v2.buildtools.server.HaxeContextFailures;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.client.HaxeDisplayClient;
import com.intellij.plugins.haxe.display.protocol.DisplayMethods;
import com.intellij.plugins.haxe.display.protocol.FileDiagnostics;
import com.intellij.plugins.haxe.display.protocol.InitializeResult;
import com.intellij.plugins.haxe.display.protocol.server.ServerMemory;
import com.intellij.plugins.haxe.display.transport.DisplayRequestException;
import com.intellij.plugins.haxe.display.transport.DisplayResponse;
import com.intellij.plugins.haxe.display.transport.HaxeDisplayTransport;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.PsiErrorElementUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// IDE glue for the display protocol: resolves a haxe source file's build
/// context into display base args, keeps the compilation server running and
/// capability-gated, and exposes the requests the editor features need.
///
/// HXML build files supply their args directly. Lime-family files (openfl,
/// lime, hxp) go through `haxelib run <tool> display <file> <target>`:
/// its hxml output IS the evaluated compiler argument set (macros, libs and
/// conditional sources included), cached per build-file stamp. NMML files use
/// the last landed `nme prepare` evaluation, whose generated hxml references
/// the evaluation's retained prepared directory.
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeCompilerDisplayService {

  /**
   * The read-action half of a request. Either {@code args} is known already
   * (HXML, NMML) or {@code lime} still needs resolving on a background thread.
   * The container's define overrides are applied to whichever argument list
   * results.
   */
  public record DisplayContext(@Nullable List<String> args,
                               @Nullable LimeDisplaySpec lime,
                               @Nullable String sdkName,
                               @NotNull HaxeDisplayConfiguration.DefineOverrides overrides,
                               @NotNull String containerId) {
  }

  /** A pending lime-display resolution, captured under the read lock. */
  public record LimeDisplaySpec(@NotNull String directory,
                                @NotNull String fileName,
                                @NotNull String tool,
                                @NotNull String targetFlag,
                                long fileStamp) {
  }

  /** Capability of the currently running server; re-checked when the port changes. */
  private record Capability(int port, @NotNull Set<String> methods,
                            @Nullable InitializeResult.SemVer haxeVersion) {
  }

  /** A connected, capability-checked client with its resolved args. */
  record Connected(@NotNull HaxeDisplayClient client, @NotNull List<String> args, int port) {
  }

  private record CachedLimeArgs(long fileStamp, @NotNull String targetFlag, @NotNull List<String> args) {
  }

  private static final int LIME_DISPLAY_TIMEOUT_MS = 60_000;
  private static final int CONTEXT_COMPILE_TIMEOUT_MS = 120_000;

  private final Project project;
  private final Map<String, CachedLimeArgs> limeArgsCache = new ConcurrentHashMap<>();
  /** Contexts already warmed with a compile this session (the server's module cache needs one). */
  private final Set<String> compiledContexts = ConcurrentHashMap.newKeySet();
  private volatile Capability capability;

  public HaxeCompilerDisplayService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerDisplayService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerDisplayService.class);
  }

  @NotNull
  public Project getProject() {
    return project;
  }

  /** The connected server's haxe version; null before the first successful initialize. */
  @Nullable
  public InitializeResult.SemVer connectedHaxeVersion() {
    Capability known = capability;
    return known != null ? known.haxeVersion() : null;
  }

  /**
   * The display context of a haxe source file, derived from its module's
   * current build file. Null when the file has no supported build context.
   * Call in a read action; does not touch the network or spawn processes.
   */
  @Nullable
  public DisplayContext contextFor(@NotNull VirtualFile file) {
    Module module = ModuleUtilCore.findModuleForFile(file, project);
    return module == null ? null : contextFor(module);
  }

  /**
   * The display context of a module's current build file — for callers that
   * have no source file in hand (the type catalog enumerates per module).
   * Same contract as {@link #contextFor(VirtualFile)}.
   */
  @Nullable
  public DisplayContext contextFor(@NotNull Module module) {
    VirtualFile buildFile = currentBuildFile(module);
    if (buildFile == null || buildFile.getParent() == null) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, buildFile);
    // a plain hxp script generates its compiler args in code - nothing to derive statically
    if (type == null || type == HaxeBuildFileType.HXP_SCRIPT) return null;

    String containerId = HaxeContainers.containerIdFor(project, buildFile);
    HaxeEnvironmentStore environment = HaxeEnvironmentStore.getInstance(project);
    // the per-container server opt-out covers display requests too - compiler
    // diagnostics ride the same server the module's builds would use
    if (!environment.isUsingCompilationServer(containerId)) return null;
    String sdkName = environment.getSdkName(containerId);
    String directory = buildFile.getParent().getPath();
    HaxeDisplayConfiguration.DefineOverrides overrides = HaxeDisplayConfiguration.overridesFor(project, containerId);

    if (type == HaxeBuildFileType.HXML) {
      // hxml paths resolve against the file's work directory, not its folder
      String hxmlDirectory = HaxeBuildWorkDirectories.workDirectory(project, buildFile);
      if (hxmlDirectory == null) return null;
      return new DisplayContext(hxmlContextArgs(buildFile, hxmlDirectory), null, sdkName, overrides, containerId);
    }
    if (type == HaxeBuildFileType.NMML) {
      return nmeContext(buildFile, directory, sdkName, overrides, containerId);
    }
    String tool = LimeProjects.toolFor(type);
    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile);
    LimeDisplaySpec lime =
      new LimeDisplaySpec(directory, buildFile.getName(), tool, targetFlag, buildFile.getModificationStamp());
    return new DisplayContext(null, lime, sdkName, overrides, containerId);
  }

  /**
   * HXML context args. A single-section file is passed as a file reference,
   * which the server expands itself. A {@code --next} chain is narrowed to the
   * SELECTED section, spelled out as compiler arguments.
   *
   * Passing a chained file would make every server request carry ALL
   * sections: each context compile (cache warm-up, failure probes, every
   * request) would then build every target in turn and stall later
   * {@code --connect} builds behind it, and diagnostics would answer for the
   * first section instead of the one the tool window follows.
   */
  @NotNull
  private List<String> hxmlContextArgs(@NotNull VirtualFile buildFile, @NotNull String directory) {
    List<String> sectionArguments = HaxeBuildSections.selectedSectionArguments(project, buildFile);
    if (sectionArguments == null) {
      return List.of("--cwd", directory, HaxeBuildWorkDirectories.fileArgument(buildFile, directory));
    }
    List<String> args = new ArrayList<>(List.of("--cwd", directory));
    args.addAll(sectionArguments);
    return List.copyOf(args);
  }

  /**
   * NMML context from the last landed `nme prepare` evaluation: its generated
   * hxml (referencing the evaluation's retained prepared directory) becomes
   * the argument list. Null until an evaluation lands — the tool window
   * schedules one on every scan, so the context appears shortly after a
   * project opens.
   */
  @Nullable
  private DisplayContext nmeContext(@NotNull VirtualFile buildFile,
                                    @NotNull String directory,
                                    @Nullable String sdkName,
                                    @NotNull HaxeDisplayConfiguration.DefineOverrides overrides,
                                    @NotNull String containerId) {
    String targetFlag = NmeProjects.selectedTargetFlag(project, buildFile);
    HaxeNmeProjectInfoService.Evaluation evaluation = HaxeNmeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(new HaxeBuildFile(buildFile, HaxeBuildFileType.NMML), targetFlag, sdkName, () -> { });
    if (evaluation == null) return null;

    List<String> args = new ArrayList<>(List.of("--cwd", directory));
    args.addAll(HxmlArguments.parseLines(evaluation.hxmlContent().lines().toList()));
    return new DisplayContext(List.copyOf(args), null, sdkName, overrides, containerId);
  }

  /**
   * Diagnostics for one file. {@code contents} carries the (diverged) editor
   * buffer — the file is invalidated on the server first, since a cached
   * module otherwise shadows the supplied contents. Null when the server is
   * unavailable or lacks the diagnostics method. Call on a background thread.
   */
  @Nullable
  public List<FileDiagnostics> diagnostics(@NotNull DisplayContext context,
                                           @NotNull String filePath,
                                           @Nullable String contents) {
    Connected connected = connectFor(context, DisplayMethods.DIAGNOSTICS);
    if (connected == null) return null;
    try {
      if (contents != null) {
        connected.client().invalidate(connected.args(), filePath);
      }
      List<FileDiagnostics> results = connected.client().diagnostics(connected.args(), filePath, contents);
      HaxeContextFailures.getInstance(project).record(context.containerId(), null);
      return results;
    } catch (DisplayRequestException e) {
      String failure = explainRequestFailure(connected, e);
      log.info("display/diagnostics failed: " + failure);
      HaxeContextFailures.getInstance(project).record(context.containerId(), failure);
      return null;
    }
  }

  /**
   * An empty display response usually means the build context itself does
   * not compile, and the JSON-RPC channel then carries no reason at all. A
   * plain compile of the same arguments does report the errors, so one is run
   * to explain the failure (for example a define override that makes a
   * library uncompilable).
   */
  @NotNull
  private static String explainRequestFailure(@NotNull Connected connected, @NotNull DisplayRequestException failure) {
    String message = StringUtil.notNullize(failure.getMessage());
    if (!message.endsWith("empty response")) {
      return message;
    }
    try {
      DisplayResponse compiled = compileContext(connected);
      String errors = compiled.hasError() ? compiled.payload().strip() : "";
      return errors.isEmpty() ? message
                              : HaxeBundle.message("haxe.display.context.compile.failed", errors);
    } catch (DisplayRequestException probeFailure) {
      return message;
    }
  }

  /**
   * Whole-project diagnostics: the sweep that surfaces parse errors in OTHER
   * files, which the per-file request stays silent about. Uses the disk state
   * of every file. Null when the server is unavailable. Call on a background
   * thread.
   */
  @Nullable
  public List<FileDiagnostics> projectDiagnostics(@NotNull DisplayContext context) {
    Connected connected = connectFor(context, DisplayMethods.DIAGNOSTICS);
    if (connected == null) return null;
    try {
      return connected.client().projectDiagnostics(connected.args());
    } catch (DisplayRequestException e) {
      log.info("whole-project diagnostics failed: " + e.getMessage());
      return null;
    }
  }

  /**
   * Whether the file parses cleanly (no {@code PsiErrorElement}). New server
   * work is pointless while it does not: the compiler fails on the same text,
   * and the parser's own error highlighting already marks it. Reading cached
   * compiler results stays fine. Cached per PSI modification; call in a read
   * action.
   */
  public static boolean isSyntaxClean(@NotNull Project project, @NotNull VirtualFile file) {
    return !PsiErrorElementUtil.hasErrors(project, file);
  }

  /** A stable identity of a context's argument source, used as a cache key by the sibling services. */
  @NotNull
  static String contextKey(@NotNull DisplayContext context) {
    String base;
    if (context.args() != null) {
      base = String.join(" ", context.args());
    }
    else {
      LimeDisplaySpec lime = context.lime();
      base = lime != null ? lime.directory() + "/" + lime.fileName() + "@" + lime.targetFlag() : "?";
    }
    String overrides = context.overrides().signature();
    return overrides.isEmpty() ? base : base + "|" + overrides;
  }

  // --- args resolution ---

  @Nullable
  private List<String> resolveArgs(@NotNull DisplayContext context) {
    List<String> base;
    if (context.args() != null) {
      base = context.args();
    }
    else {
      LimeDisplaySpec lime = context.lime();
      base = lime != null ? limeArgsFor(lime, context.sdkName()) : null;
    }
    return base == null ? null : HaxeDisplayConfiguration.applyOverrides(base, context.overrides());
  }

  // TODO: keyed on the project file's stamp only - an edited lib
  //  include.xml does not bust this cache.
  @Nullable
  private List<String> limeArgsFor(@NotNull LimeDisplaySpec spec, @Nullable String sdkName) {
    String cacheKey = spec.directory() + "/" + spec.fileName();
    CachedLimeArgs cached = limeArgsCache.get(cacheKey);
    if (cached != null && cached.fileStamp() == spec.fileStamp() && cached.targetFlag().equals(spec.targetFlag())) {
      return cached.args();
    }
    List<String> args = runLimeDisplay(spec, sdkName);
    if (args != null) {
      limeArgsCache.put(cacheKey, new CachedLimeArgs(spec.fileStamp(), spec.targetFlag(), args));
    }
    return args;
  }

  @Nullable
  private List<String> runLimeDisplay(@NotNull LimeDisplaySpec spec, @Nullable String sdkName) {
    String haxelib = HaxeToolPathResolver.resolveHaxelibExecutable(project, sdkName);
    List<String> args = LimeProjects.displayArguments(
      haxelib, spec.tool(), spec.directory(), spec.fileName(), spec.targetFlag(), LIME_DISPLAY_TIMEOUT_MS);
    if (args == null) {
      log.info("lime display failed for " + spec.fileName());
    }
    return args;
  }

  // --- server connection ---

  /**
   * Memory statistics of the compilation server already RUNNING on {@code port}.
   * Unlike the context-based requests, {@code server/memory} needs no build
   * context: it reports per-context sizes for whatever the server has
   * compiled. Performs a socket round-trip; call on a background thread.
   */
  @NotNull
  public static ServerMemory fetchServerMemory(int port) throws DisplayRequestException {
    return new HaxeDisplayClient(HaxeCompilationServerManager.SERVER_HOST, port).serverMemory(List.of());
  }

  /**
   * Resolves the context's args, ensures the server runs and checks (once per
   * server) that it supports {@code method}; null when any of that fails.
   * Background threads only.
   */
  @Nullable
  Connected connectFor(@NotNull DisplayContext context, @NotNull String method) {
    List<String> args = resolveArgs(context);
    if (args == null) return null;
    int port = HaxeCompilationServerManager.getInstance(project).ensureRunning(context.sdkName());
    if (port <= 0) return null;
    HaxeDisplayClient client = new HaxeDisplayClient(HaxeCompilationServerManager.SERVER_HOST, port);
    String serverId = HaxeToolPathResolver.resolveHaxeExecutable(project, context.sdkName());
    client.setObserver((requestMethod, millis, success) ->
                         HaxeServerMetrics.getInstance(project).record(serverId, millis, success));

    Capability known = capability;
    if (known == null || known.port() != port) {
      InitializeResult initialized = initializeWithRetry(client, args);
      if (initialized == null) return null;
      known = new Capability(port, Set.copyOf(initialized.methods()), initialized.haxeVersion());
      log.info("haxe display protocol " + initialized.protocolVersion()
               + " (haxe " + initialized.haxeVersion() + "), "
               + known.methods().size() + " methods");
      capability = known;
    }
    return known.methods().contains(method) ? new Connected(client, args, port) : null;
  }

  /** A freshly spawned server needs a moment to bind its port; retry briefly before giving up. */
  @Nullable
  private static InitializeResult initializeWithRetry(@NotNull HaxeDisplayClient client, @NotNull List<String> args) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (true) {
      try {
        return client.initialize(args);
      } catch (DisplayRequestException e) {
        if (System.currentTimeMillis() > deadline) {
          log.info("display initialize failed: " + e.getMessage());
          return null;
        }
        try {
          Thread.sleep(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
  }

  // --- context warm-up ---

  /**
   * The server's module cache only fills from an actual compile, so each
   * context is compiled once per session. A FAILED compile (broken code) does
   * not count: marking it warm would leave module lookups broken until a
   * purge, even after the code is fixed. The next call retries instead.
   * Background threads only.
   */
  boolean ensureContextCompiled(@NotNull Connected connected, @NotNull String contextKey) {
    if (compiledContexts.contains(contextKey)) return true;
    try {
      DisplayResponse compiled = compileContext(connected);
      if (compiled.hasError()) {
        log.info("context warm-up compile reported errors - will retry: " + compiled.payload());
        return false;
      }
      compiledContexts.add(contextKey);
      return true;
    } catch (DisplayRequestException e) {
      log.info("context warm-up compile failed: " + e.getMessage());
      return false;
    }
  }

  /** A plain {@code --no-output} compile of the connected context through the server. */
  @NotNull
  private static DisplayResponse compileContext(@NotNull Connected connected) throws DisplayRequestException {
    List<String> compileArgs = new ArrayList<>(connected.args());
    compileArgs.add("--no-output");
    return HaxeDisplayTransport.request(HaxeCompilationServerManager.SERVER_HOST, connected.port(), compileArgs,
                                        CONTEXT_COMPILE_TIMEOUT_MS);
  }

  /** Forget which contexts were warmed; the next module lookup re-compiles. */
  public void resetCompiledContexts() {
    compiledContexts.clear();
  }

  /** The file on disk behind an element; a completion copy maps back to its original. */
  @Nullable
  static VirtualFile physicalFileOf(@NotNull PsiElement element) {
    PsiFile file = element.getContainingFile();
    return file != null ? file.getOriginalFile().getVirtualFile() : null;
  }

  // --- build file resolution ---

  /**
   * The module's current build file: the project's active file when this
   * module owns it, else the module's Build command file.
   */
  @Nullable
  private VirtualFile currentBuildFile(@NotNull Module module) {
    VirtualFile active = fileIfOwned(HaxeKnownBuildFiles.effectiveActivePath(project), module);
    if (active != null) return active;
    HaxeEnvironmentStore.CompileCommand command =
      HaxeEnvironmentStore.getInstance(project).getCompileCommand(module.getName());
    return command != null ? fileIfOwned(command.buildFilePath(), module) : null;
  }

  @Nullable
  private VirtualFile fileIfOwned(@Nullable String path, @NotNull Module module) {
    if (path == null || path.isBlank()) return null;
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.isValid()) return null;
    return HaxeContainers.containerIdFor(project, file).equals(module.getName()) ? file : null;
  }
}
