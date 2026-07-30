package com.intellij.plugins.haxe.v2.buildtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeDefine;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import com.intellij.plugins.haxe.v2.buildsystem.HxmlFileParser;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import com.intellij.util.concurrency.AppExecutorUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * Resolves the effective compiler configuration of Lime/OpenFL/HXP project files.
 * Primary path: the bundled LimeProjectParser jar (tools/LimeProjectParser.jar,
 * run on the IDE's own JRE) evaluates the project natively and returns structured
 * JSON — defines, haxelibs WITH their identity, sources and app data. Fallback
 * when the jar is missing or fails: {@code haxelib run lime|openfl display},
 * whose hxml output flattens haxelibs into classpaths.
 *
 * Results are cached per (file, target, toolchain) and refreshed in the
 * background - callers get the cached value (possibly stale, possibly null on
 * first ask) immediately and a callback once a refresh lands. A failed run is
 * cached as null until the file changes, so a broken toolchain never causes
 * refresh loops.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeLimeDisplayService implements Disposable {

  private static final int DISPLAY_TIMEOUT_MS = 60_000;
  private static final String PLUGIN_ID = "com.intellij.plugins.haxe";
  private static final String PARSER_RELATIVE_PATH = "tools/LimeProjectParser.jar";

  // failed/fallback evaluations retry this many times (per file revision) before
  // the result is accepted as final - keeps transient tool failures from
  // sticking without allowing refresh loops
  private static final int MAX_ATTEMPTS = 3;

  private record CacheKey(@NotNull String filePath, @NotNull String targetFlag, @NotNull String haxelibPath) {
  }

  /** {@code authoritative} = the parser tool succeeded, or no tool is bundled (legacy is the best available). */
  private record CacheValue(long modificationStamp, @Nullable HaxeBuildFileInfo info, boolean authoritative, int attempts) {
  }

  private final Project project;
  private final Map<CacheKey, CacheValue> cache = new ConcurrentHashMap<>();
  private final Set<CacheKey> inFlight = ConcurrentHashMap.newKeySet();
  // every caller waiting on an in-flight evaluation gets its callback fired -
  // dropping later callers' callbacks left e.g. the tree unrefreshed whenever
  // the define-context service happened to schedule the same evaluation first
  private final Map<CacheKey, List<Runnable>> pendingCallbacks = new ConcurrentHashMap<>();
  private final ExecutorService executor =
    AppExecutorUtil.createBoundedApplicationPoolExecutor("Haxe lime display", 1);

  public HaxeLimeDisplayService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeLimeDisplayService getInstance(@NotNull Project project) {
    return project.getService(HaxeLimeDisplayService.class);
  }

  /**
   * The file's effective configuration for the target, or null when not resolved
   * (yet). Schedules a background run when the cache is stale; {@code onUpdated}
   * fires on the EDT after the run completes. Safe to call from read actions.
   */
  @Nullable
  public HaxeBuildFileInfo getCachedOrSchedule(@NotNull HaxeBuildFile buildFile,
                                               @NotNull String targetFlag,
                                               @Nullable String preferredSdkName,
                                               @NotNull Runnable onUpdated) {
    String haxelibPath = HaxeToolPathResolver.resolveHaxelibExecutable(project, preferredSdkName);
    String haxePath = HaxeToolPathResolver.resolveHaxeExecutable(project, preferredSdkName);
    CacheKey key = new CacheKey(buildFile.file().getPath(), targetFlag, haxelibPath);
    long stamp = buildFile.file().getModificationStamp();

    CacheValue cached = cache.get(key);
    boolean fresh = cached != null && cached.modificationStamp() == stamp;
    if (fresh && (cached.authoritative() || cached.attempts() >= MAX_ATTEMPTS)) {
      return cached.info();
    }
    schedule(key, buildFile, stamp, haxePath, fresh ? cached.attempts() : 0, onUpdated);
    // serve the stale value while the refresh runs
    return cached != null ? cached.info() : null;
  }

  public void clearCache() {
    cache.clear();
  }

  /** Drops every cached evaluation of one build file (all targets/toolchains), forcing a re-run on the next ask. */
  public void invalidate(@NotNull String filePath) {
    cache.keySet().removeIf(key -> key.filePath().equals(filePath));
  }

  private void schedule(@NotNull CacheKey key,
                        @NotNull HaxeBuildFile buildFile,
                        long stamp,
                        @NotNull String haxePath,
                        int previousAttempts,
                        @NotNull Runnable onUpdated) {
    pendingCallbacks.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(onUpdated);
    if (!inFlight.add(key)) {
      // an evaluation is already running; it fires the callback registered above
      return;
    }

    String tool = buildFile.type() == HaxeBuildFileType.OPENFL ? "openfl" : "lime";
    VirtualFile parent = buildFile.file().getParent();
    String workDirectory = parent != null ? parent.getPath() : project.getBasePath();
    String filePath = buildFile.file().getPath();
    String fileName = buildFile.file().getName();

    executor.execute(() -> {
      List<Runnable> callbacks;
      try {
        Path parserJar = bundledParserJar();
        HaxeBuildFileInfo info = null;
        boolean authoritative = parserJar == null;
        if (parserJar != null) {
          info = runParserTool(parserJar, key, filePath, workDirectory, haxePath);
          authoritative = info != null;
        }
        if (info == null) {
          // resilience over purity: a tool failure falls back to lime display
          info = runDisplay(key, tool, fileName, workDirectory);
        }
        cache.put(key, new CacheValue(stamp, info, authoritative, previousAttempts + 1));
      }
      finally {
        // callbacks drained BEFORE the in-flight flag drops: an ask arriving in
        // between re-registers and starts a fresh evaluation of its own
        callbacks = pendingCallbacks.remove(key);
        inFlight.remove(key);
      }
      List<Runnable> toRun = callbacks != null ? callbacks : List.of();
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!project.isDisposed()) {
          toRun.forEach(Runnable::run);
        }
      });
    });
  }

  // --- bundled parser tool path ---

  @Nullable
  private HaxeBuildFileInfo runParserTool(@NotNull Path parserJar,
                                          @NotNull CacheKey key,
                                          @NotNull String filePath,
                                          @Nullable String workDirectory,
                                          @NotNull String haxePath) {
    List<String> command = new ArrayList<>(List.of(
      javaExecutable(), "-jar", parserJar.toString(),
      filePath, "--target", key.targetFlag(), "--haxe", haxePath, "--haxelib", key.haxelibPath()));
    for (String seed : seedDefines(key.targetFlag())) {
      command.add("-D");
      command.add(seed);
    }
    GeneralCommandLine commandLine = new GeneralCommandLine(command).withWorkDirectory(workDirectory);
    try {
      ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(DISPLAY_TIMEOUT_MS);
      if (output.isTimeout() || output.getExitCode() != 0) {
        log.warn("LimeProjectParser failed for " + filePath + " (" + key.targetFlag() + "): "
                 + output.getStderr().lines().findFirst().orElse("exit code " + output.getExitCode()));
        return null;
      }
      return parseToolOutput(output.getStdout(), key.targetFlag());
    }
    catch (ExecutionException e) {
      log.warn("LimeProjectParser could not run for " + filePath + ": " + e.getMessage());
      return null;
    }
  }

  @Nullable
  private static HaxeBuildFileInfo parseToolOutput(@NotNull String stdout, @NotNull String targetFlag) {
    try {
      JsonNode root = new ObjectMapper().readTree(StringUtil.trimTrailing(stdout));

      List<HaxeDefine> defines = new ArrayList<>();
      root.path("defines").properties().forEach(
        entry -> defines.add(new HaxeDefine(entry.getKey(), StringUtil.nullize(entry.getValue().asText()))));

      List<HaxeLibDependency> libraries = new ArrayList<>();
      root.path("haxelibs").forEach(
        library -> libraries.add(new HaxeLibDependency(library.path("name").asText(),
                                                       StringUtil.nullize(library.path("version").asText()))));

      List<String> classpaths = new ArrayList<>();
      root.path("sources").forEach(source -> classpaths.add(source.asText()));

      String appPath = root.path("app").path("path").asText("Export");
      String appFile = root.path("app").path("file").asText("");
      return new HaxeBuildFileInfo(haxeTargetFor(targetFlag),
                                   targetOutputFor(targetFlag, appPath, appFile),
                                   List.copyOf(defines), List.copyOf(libraries), List.copyOf(classpaths));
    }
    catch (Exception e) {
      log.warn("LimeProjectParser output was not parseable: " + e.getMessage());
      return null;
    }
  }

  /** The haxe compilation target behind a lime CLI target id. */
  @Nullable
  private static HaxeTarget haxeTargetFor(@NotNull String targetFlag) {
    return switch (targetFlag) {
      case "hl" -> HaxeTarget.HL;
      case "html5" -> HaxeTarget.JAVA_SCRIPT;
      case "flash", "air" -> HaxeTarget.FLASH;
      case "neko" -> HaxeTarget.NEKO;
      case "java" -> HaxeTarget.JAVA;
      case "cppia" -> HaxeTarget.CPPIA;
      case "cs" -> HaxeTarget.CSHARP;
      case "windows", "mac", "linux", "android", "ios" -> HaxeTarget.CPP;
      default -> null;
    };
  }

  /**
   * The compile artifact per lime's export layout ({@code <app path>/<target>/...});
   * only the targets Build &amp; run can launch need one.
   */
  @Nullable
  private static String targetOutputFor(@NotNull String targetFlag, @NotNull String appPath, @NotNull String appFile) {
    return switch (targetFlag) {
      case "hl" -> appPath + "/hl/obj/ApplicationMain.hl";
      case "html5" -> appPath + "/html5/bin/" + (appFile.isEmpty() ? "index" : appFile) + ".js";
      case "flash" -> appPath + "/flash/bin/" + (appFile.isEmpty() ? "Main" : appFile) + ".swf";
      // desktop cpp: lime copies the built executable into bin, named after
      // <app file>, independent of -debug (unlike raw hxcpp's Main-debug.exe)
      case "windows" -> appFile.isEmpty() ? null : appPath + "/windows/bin/" + appFile + ".exe";
      case "linux" -> appFile.isEmpty() ? null : appPath + "/linux/bin/" + appFile;
      // TODO mac: the artifact is a .app bundle (Contents/MacOS/<app file>) - needs bundle-aware launch
      default -> null;
    };
  }

  /**
   * Approximates the condition defines lime seeds before parsing (target id plus
   * its platform family). The planned matrix comparison lane tightens this list
   * against real `lime display` output.
   */
  @NotNull
  private static List<String> seedDefines(@NotNull String targetFlag) {
    List<String> seeds = new ArrayList<>();
    seeds.add(targetFlag);
    switch (targetFlag) {
      case "html5" -> seeds.add("web");
      case "android", "ios" -> {
        seeds.add("mobile");
        seeds.add("native");
      }
      case "windows", "mac", "linux" -> {
        seeds.add("desktop");
        seeds.add("native");
      }
      case "hl", "neko", "java", "cs" -> {
        seeds.add("desktop");
        // pseudo-targets run on the host platform, which lime also defines
        seeds.add(SystemInfo.isWindows ? "windows" : SystemInfo.isMac ? "mac" : "linux");
      }
      default -> { }
    }
    return seeds;
  }

  @NotNull
  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", SystemInfo.isWindows ? "java.exe" : "java").toString();
  }

  /** The evaluator jar shipped inside the plugin directory, or null when absent (fallback applies). */
  @Nullable
  private static Path bundledParserJar() {
    //TODO  we must replace this API once there is an alternative made available
    // ref: https://platform.jetbrains.com/t/pluginmanagercore-getplugin-is-now-internal/4272/32
    IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
    Path jar = plugin != null ? plugin.getPluginPath().resolve(PARSER_RELATIVE_PATH) : null;
    return jar != null && Files.isRegularFile(jar) ? jar : null;
  }

  // --- legacy lime display fallback ---

  @Nullable
  private HaxeBuildFileInfo runDisplay(@NotNull CacheKey key,
                                       @NotNull String tool,
                                       @NotNull String fileName,
                                       @Nullable String workDirectory) {
    GeneralCommandLine commandLine =
      new GeneralCommandLine(key.haxelibPath(), "run", tool, "display", fileName, key.targetFlag())
        .withWorkDirectory(workDirectory);
    try {
      ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(DISPLAY_TIMEOUT_MS);
      if (output.isTimeout() || output.getExitCode() != 0) {
        log.warn(tool + " display failed for " + fileName + " (" + key.targetFlag() + "): "
                 + output.getStderr().lines().findFirst().orElse("exit code " + output.getExitCode()));
        return null;
      }
      return HxmlFileParser.parse(output.getStdout(), path -> null);
    }
    catch (ExecutionException e) {
      log.warn(tool + " display could not run for " + fileName + ": " + e.getMessage());
      return null;
    }
  }

  @Override
  public void dispose() {
    executor.shutdownNow();
    cache.clear();
  }
}
