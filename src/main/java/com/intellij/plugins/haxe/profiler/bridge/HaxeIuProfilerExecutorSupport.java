package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.RunExecutorSettings;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration.Lane;
import com.intellij.plugins.haxe.profiler.HaxeProfilerExecutorSupport;
import com.intellij.profiler.DefaultProfilerExecutorGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves profiler child executors back to their Haxe profiler
 * configurations: the HashLink entry's sampling rate, the hxcpp entry's
 * compile additions (its profiler defines plus the bundled start/stop
 * bootstrap macro, extracted beside the IDE's system directory), and the
 * lane-matching executor for tool-window Profile actions.
 */
public class HaxeIuProfilerExecutorSupport implements HaxeProfilerExecutorSupport {

  private static final Logger LOG = Logger.getInstance(HaxeIuProfilerExecutorSupport.class);
  /** The bundled bootstrap: the init macro wrapping main/System.exit, its idempotent-stop runtime, and the telemetry collector. */
  private static final List<String> BOOT_RESOURCES = List.of(
    "/haxe/profilerboot/ijhaxe/ProfilerBoot.hx",
    "/haxe/profilerboot/ijhaxe/ProfilerRun.hx",
    "/haxe/profilerboot/ijhaxe/TelemetryRun.hx",
    "/haxe/profilerboot/ijhaxe/CppTelemetry.hx");

  @Override
  public @Nullable Integer hashlinkSamplesPerSecondFor(@NotNull Executor executor) {
    RunExecutorSettings settings = registeredSettings(executor.getId());
    if (!(settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings)) return null;
    if (!(profilerSettings.getState() instanceof HaxeHlProfilerConfigurationState state)) return null;
    return state.getSamplesPerSecond();
  }

  @Override
  public @Nullable List<String> hxcppProfilingAdditionsFor(@NotNull Executor executor, boolean limeFamily, @NotNull Path dumpPath) {
    RunExecutorSettings settings = registeredSettings(executor.getId());
    if (!(settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings)) return null;
    if (!(profilerSettings.getState() instanceof HaxeHxcppProfilerConfigurationState)) return null;
    Path macroRoot = extractBootMacro();
    if (macroRoot == null) return null;

    // the dump path is baked into the binary as a haxe string literal:
    // forward slashes keep backslash escaping out of the equation
    String macroCall = "ijhaxe.ProfilerBoot.use('" + dumpPath.toString().replace('\\', '/') + "')";
    String classpath = macroRoot.toString().replace('\\', '/');
    // HXCPP_TELEMETRY builds the runtime's telemetry instrumentation in
    // (alloc/GC hooks, dormant until started) - groundwork for the telemetry
    // collector lane; verified to coexist with the report profiler
    if (limeFamily) {
      // lime turns each --haxeflag value into one hxml line, where a flag
      // takes the rest of the line as its argument - spaces need no quoting
      return List.of("-DHXCPP_PROFILER", "-DHXCPP_STACK_TRACE", "-DHXCPP_TELEMETRY",
                     "--haxeflag=-cp " + classpath,
                     "--haxeflag=--macro " + macroCall);
    }
    return List.of("-D", "HXCPP_PROFILER", "-D", "HXCPP_STACK_TRACE", "-D", "HXCPP_TELEMETRY",
                   "-cp", classpath,
                   "--macro", macroCall);
  }

  @Override
  public @Nullable Executor profilerExecutorFor(@NotNull RunConfiguration configuration) {
    if (!(configuration instanceof HaxeProfilableRunConfiguration profilable)) return null;
    DefaultProfilerExecutorGroup group = DefaultProfilerExecutorGroup.Companion.getInstance();
    if (group == null) return null;
    String typeId = typeIdFor(profilable.profilingLane());

    for (Executor child : group.childExecutors()) {
      RunExecutorSettings settings = group.getRegisteredSettings(child.getId());
      if (settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings
          && typeId.equals(profilerSettings.getState().getConfigurationTypeId())) {
        return child;
      }
    }
    return null;
  }

  private static String typeIdFor(Lane lane) {
    return switch (lane) {
      case HASHLINK -> HaxeHlProfilerConfigurationType.ID;
      case HXCPP -> HaxeHxcppProfilerConfigurationType.ID;
    };
  }

  @Nullable
  private static RunExecutorSettings registeredSettings(String executorId) {
    DefaultProfilerExecutorGroup group = DefaultProfilerExecutorGroup.Companion.getInstance();
    return group == null ? null : group.getRegisteredSettings(executorId);
  }

  /**
   * The classpath root holding the bundled bootstrap sources, extracted (and
   * kept current) under the IDE system directory; null when extraction fails —
   * the launch then proceeds unprofiled rather than failing the build.
   */
  @Nullable
  private static Path extractBootMacro() {
    Path root = Path.of(PathManager.getSystemPath(), "haxe-profiler");
    try {
      for (String resource : BOOT_RESOURCES) {
        // the resource path mirrors the haxe package layout under the root
        Path target = root.resolve(resource.substring(resource.lastIndexOf("/ijhaxe/") + 1));
        try (InputStream source = HaxeIuProfilerExecutorSupport.class.getResourceAsStream(resource)) {
          if (source == null) {
            LOG.warn("bundled profiler bootstrap missing: " + resource);
            return null;
          }
          byte[] content = source.readAllBytes();
          // rewrite on content change so a plugin update replaces stale extractions
          if (!Files.exists(target) || !Arrays.equals(content, Files.readAllBytes(target))) {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
          }
        }
      }
      return root;
    }
    catch (IOException e) {
      LOG.warn("could not extract the profiler bootstrap", e);
      return null;
    }
  }
}
