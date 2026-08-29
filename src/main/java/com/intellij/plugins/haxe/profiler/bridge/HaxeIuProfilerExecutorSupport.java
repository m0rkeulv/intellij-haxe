package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.execution.Executor;
import com.intellij.execution.executors.RunExecutorSettings;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.plugins.haxe.profiler.HaxeProfilerExecutorSupport;
import com.intellij.plugins.haxe.profiler.bridge.flash.HaxeFlashProfilerConfigurationState;
import com.intellij.plugins.haxe.profiler.bridge.hashlink.HaxeHlProfilerConfigurationState;
import com.intellij.plugins.haxe.profiler.bridge.hxcpp.HaxeHxcppProfilerConfigurationState;
import com.intellij.plugins.haxe.profiler.bridge.js.HaxeJsProfilerConfigurationState;
import com.intellij.plugins.haxe.profiler.bridge.tracy.HaxeHxcppTracyProfilerConfigurationState;
import com.intellij.profiler.DefaultProfilerExecutorGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Resolves profiler child executors back to their Haxe profiler
 * configurations: the HashLink entry's sampling rate, the hxcpp entry's
 * compile additions (its profiler defines plus the bundled start/stop
 * bootstrap macro, extracted beside the IDE's system directory), and the
 * lane-matching executor for tool-window Profile actions.
 */
public class HaxeIuProfilerExecutorSupport implements HaxeProfilerExecutorSupport {

  private static final Logger LOG = Logger.getInstance(HaxeIuProfilerExecutorSupport.class);
  /** The bundled bootstrap: the init macro wrapping main/System.exit, its idempotent-stop runtime, and the collectors. */
  private static final List<String> BOOT_RESOURCES = List.of(
    "/haxe/profilerboot/ijhaxe/ProfilerBoot.hx",
    "/haxe/profilerboot/ijhaxe/ProfilerRun.hx",
    "/haxe/profilerboot/ijhaxe/TelemetryRun.hx",
    "/haxe/profilerboot/ijhaxe/CppTelemetry.hx");

  @Override
  public @Nullable Integer hashlinkSamplesPerSecondFor(@NotNull Executor executor) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeHlProfilerConfigurationState state)) return null;
    return state.getSamplesPerSecond();
  }

  @Override
  public @Nullable List<String> hxcppProfilingAdditionsFor(@NotNull Executor executor, boolean limeFamily, @NotNull Path dumpPath) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeHxcppProfilerConfigurationState)) return null;
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
  public @Nullable List<String> hxcppTracyAdditionsFor(@NotNull Executor executor, boolean limeFamily) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeHxcppTracyProfilerConfigurationState state)) return null;
    // HXCPP_TELEMETRY is the master switch, HXCPP_TRACY picks the tracy
    // implementation, and the zones need the stack-frame instrumentation.
    // HXCPP_STACK_LINE is REQUIRED, not optional: TelemetryTracy.cpp reads
    // StackFrame::lineNumber unconditionally, and that member only exists
    // with the define - a tracy build without it fails to compile.
    // HXCPP_TRACY_MEMORY adds the GC alloc/free hooks feeding the memory
    // curves and GC lane; the settings page toggles it (runtime cost).
    List<String> additions = new ArrayList<>();
    List<String> defines = new ArrayList<>(List.of("HXCPP_TELEMETRY", "HXCPP_TRACY",
                                                   "HXCPP_STACK_TRACE", "HXCPP_STACK_LINE"));
    if (state.isCaptureMemory()) {
      defines.add(2, "HXCPP_TRACY_MEMORY");
    }
    for (String define : defines) {
      if (limeFamily) {
        additions.add("-D" + define);
      }
      else {
        additions.add("-D");
        additions.add(define);
      }
    }
    return additions;
  }

  @Override
  public @Nullable List<String> flashProfilingAdditionsFor(@NotNull Executor executor, boolean limeFamily) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeFlashProfilerConfigurationState)) return null;
    // advanced-telemetry embeds the EnableTelemetry swf tag (swf-version 17+):
    // the runtime then streams its own Scout telemetry - frames, render
    // spans, sampler stacks, memory, GC - to the address in ~/.telemetry.cfg,
    // which the capture points at itself. No code is injected; the sampler
    // ticks only on the debugger runtime, so adl launches in debug mode.
    return limeFamily
           ? List.of("--haxeflag=-D advanced-telemetry")
           : List.of("-D", "advanced-telemetry");
  }

  @Override
  public @Nullable Integer jsSamplingIntervalUsFor(@NotNull Executor executor) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeJsProfilerConfigurationState state)) return null;
    return state.getSamplingIntervalUs();
  }

  @Override
  public @Nullable List<String> jsProfilingAdditionsFor(@NotNull Executor executor, boolean limeFamily) {
    if (!(HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeJsProfilerConfigurationState)) return null;
    // the sampled positions map back to .hx through the compiler's js
    // source map; both define spellings cover haxe versions before and
    // after the js-source-map -> source-map rename (an unknown define is
    // inert, so shipping both is safe)
    return limeFamily
           ? List.of("--haxeflag=-D js-source-map", "--haxeflag=-D source-map")
           : List.of("-D", "js-source-map", "-D", "source-map");
  }

  @Override
  public boolean hxcppTracyElevatedFor(@NotNull Executor executor) {
    // tracy's system tracing has Windows (ETW) and Linux (ftrace/perf)
    // backends only - elevating on macOS would gain nothing
    if (!SystemInfo.isWindows && !SystemInfo.isLinux) return false;
    return HaxeProfilerConfigurations.stateFor(executor) instanceof HaxeHxcppTracyProfilerConfigurationState state
           && state.isCollectProcessCpu();
  }

  @Override
  public @NotNull List<ProfilerEntry> profilerExecutorsFor(HaxeProfilableRunConfiguration.@NotNull Lane lane) {
    DefaultProfilerExecutorGroup group = DefaultProfilerExecutorGroup.Companion.getInstance();
    if (group == null) return List.of();
    Set<String> typeIds = HaxeProfilerConfigurations.typeIdsFor(lane);

    List<ProfilerEntry> entries = new ArrayList<>();
    for (Executor child : group.childExecutors()) {
      RunExecutorSettings settings = group.getRegisteredSettings(child.getId());
      if (settings instanceof DefaultProfilerExecutorGroup.ProfilerExecutorSettings profilerSettings
          && typeIds.contains(profilerSettings.getState().getConfigurationTypeId())) {
        entries.add(new ProfilerEntry(child, profilerSettings.getState().getDisplayName()));
      }
    }
    return entries;
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
