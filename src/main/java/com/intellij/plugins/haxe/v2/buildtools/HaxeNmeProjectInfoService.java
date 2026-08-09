package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import com.intellij.plugins.haxe.v2.buildsystem.HxmlFileParser;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Resolves the effective compiler configuration of NME project files (nmml).
 * The nme tool has no side-effect-free display command, but its {@code prepare}
 * command runs the full project evaluation (conditionals, include.nmml
 * transitives, asset handlers) and generates the build hxml without compiling;
 * {@code -bin} redirects everything into a temp directory so the project tree
 * stays untouched. The generated hxml flattens haxelibs into classpaths and
 * carries no -lib lines, so library identities are recovered from the
 * classpaths that point into the haxelib repository.
 *
 * Results are cached per (file, target, toolchain) and refreshed in the
 * background - callers get the cached value (possibly stale, possibly null on
 * first ask) immediately and a callback once a refresh lands. A failed run
 * retries a bounded number of times per file revision, so a broken toolchain
 * never causes refresh loops. Mirrors {@link HaxeLimeProjectInfoService}.
 */
@Service(Service.Level.PROJECT)
@CustomLog
public final class HaxeNmeProjectInfoService implements Disposable {

  private static final int PREPARE_TIMEOUT_MS = 60_000;
  private static final int MAX_ATTEMPTS = 3;
  private static final String BUILD_FILE_MARKER = "PREPARE BUILD_FILE=";

  // a haxelib-repo path segment naming an installed version: haxelib stores
  // versions with commas (6,3,149) or as "git"; the segment BEFORE it is the
  // library name (<repo>/<name>/<version>/...)
  private static final Pattern HAXELIB_VERSION_SEGMENT = Pattern.compile("\\d+(?:,\\d+)+|git");

  private record CacheKey(@NotNull String filePath, @NotNull String targetFlag, @NotNull String haxelibPath) {
  }

  private record CacheValue(long modificationStamp, @Nullable HaxeBuildFileInfo info, int attempts) {
  }

  private final Project project;
  private final Map<CacheKey, CacheValue> cache = new ConcurrentHashMap<>();
  private final Set<CacheKey> inFlight = ConcurrentHashMap.newKeySet();
  // every caller waiting on an in-flight evaluation gets its callback fired
  private final Map<CacheKey, List<Runnable>> pendingCallbacks = new ConcurrentHashMap<>();
  private final ExecutorService executor =
    AppExecutorUtil.createBoundedApplicationPoolExecutor("Haxe nme prepare", 1);

  public HaxeNmeProjectInfoService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeNmeProjectInfoService getInstance(@NotNull Project project) {
    return project.getService(HaxeNmeProjectInfoService.class);
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
    CacheKey key = new CacheKey(buildFile.file().getPath(), targetFlag, haxelibPath);
    long stamp = buildFile.file().getModificationStamp();

    CacheValue cached = cache.get(key);
    boolean fresh = cached != null && cached.modificationStamp() == stamp;
    if (fresh && (cached.info() != null || cached.attempts() >= MAX_ATTEMPTS)) {
      return cached.info();
    }
    schedule(key, buildFile, stamp, fresh ? cached.attempts() : 0, onUpdated);
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
                        int previousAttempts,
                        @NotNull Runnable onUpdated) {
    pendingCallbacks.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(onUpdated);
    if (!inFlight.add(key)) {
      // an evaluation is already running; it fires the callback registered above
      return;
    }

    VirtualFile parent = buildFile.file().getParent();
    String workDirectory = parent != null ? parent.getPath() : project.getBasePath();
    String fileName = buildFile.file().getName();

    executor.execute(() -> {
      List<Runnable> callbacks;
      try {
        HaxeBuildFileInfo info = runPrepare(key, fileName, workDirectory);
        cache.put(key, new CacheValue(stamp, info, previousAttempts + 1));
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

  @Nullable
  private HaxeBuildFileInfo runPrepare(@NotNull CacheKey key,
                                       @NotNull String fileName,
                                       @Nullable String workDirectory) {
    Path tempBin = null;
    try {
      tempBin = Files.createTempDirectory("haxe-nme-prepare");
      List<String> command = List.of(key.haxelibPath(), "run", "nme", "prepare",
                                     fileName, key.targetFlag(), "-bin", tempBin.toString());
      GeneralCommandLine commandLine = new GeneralCommandLine(command).withWorkDirectory(workDirectory);
      ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(PREPARE_TIMEOUT_MS);
      if (output.isTimeout() || output.getExitCode() != 0) {
        log.warn("nme prepare failed for " + fileName + " (" + key.targetFlag() + "): "
                 + output.getStderr().lines().findFirst().orElse("exit code " + output.getExitCode()));
        return null;
      }
      return parsePreparedBuildFile(output.getStdout(), workDirectory, fileName);
    }
    catch (Exception e) {
      log.warn("nme prepare could not run for " + fileName + ": " + e.getMessage());
      return null;
    }
    finally {
      if (tempBin != null) {
        FileUtil.delete(tempBin.toFile());
      }
    }
  }

  @Nullable
  private static HaxeBuildFileInfo parsePreparedBuildFile(@NotNull String stdout,
                                                          @Nullable String workDirectory,
                                                          @NotNull String fileName) throws Exception {
    String buildFilePath = stdout.lines()
      .filter(line -> line.startsWith(BUILD_FILE_MARKER))
      .map(line -> line.substring(BUILD_FILE_MARKER.length()).trim())
      .findFirst()
      .orElse(null);
    if (buildFilePath == null) {
      log.warn("nme prepare printed no build file marker for " + fileName);
      return null;
    }

    Path hxml = Path.of(buildFilePath);
    if (!hxml.isAbsolute() && workDirectory != null) {
      hxml = Path.of(workDirectory).resolve(hxml);
    }
    HaxeBuildFileInfo parsed = HxmlFileParser.parse(Files.readString(hxml), path -> null);
    List<HaxeLibDependency> libraries = withDerivedLibraries(parsed.libraries(), parsed.classpaths());
    return new HaxeBuildFileInfo(parsed.target(), parsed.targetOutput(), parsed.defines(), libraries,
                                 parsed.classpaths());
  }

  /** The hxml's explicit -lib entries plus the identities its haxelib-repo classpaths encode. */
  @NotNull
  private static List<HaxeLibDependency> withDerivedLibraries(@NotNull List<HaxeLibDependency> declared,
                                                              @NotNull List<String> classpaths) {
    Map<String, HaxeLibDependency> byName = new LinkedHashMap<>();
    for (HaxeLibDependency library : declared) {
      byName.putIfAbsent(library.name(), library);
    }
    for (String classpath : classpaths) {
      HaxeLibDependency library = libraryOfRepoClasspath(classpath);
      if (library != null) {
        byName.putIfAbsent(library.name(), library);
      }
    }
    return List.copyOf(byName.values());
  }

  @Nullable
  private static HaxeLibDependency libraryOfRepoClasspath(@NotNull String classpath) {
    String[] segments = classpath.split("[/\\\\]");
    for (int i = 1; i < segments.length; i++) {
      if (HAXELIB_VERSION_SEGMENT.matcher(segments[i]).matches()) {
        String version = "git".equals(segments[i]) ? null : segments[i].replace(',', '.');
        return new HaxeLibDependency(segments[i - 1], version);
      }
    }
    return null;
  }

  @Override
  public void dispose() {
    executor.shutdownNow();
    cache.clear();
  }
}
