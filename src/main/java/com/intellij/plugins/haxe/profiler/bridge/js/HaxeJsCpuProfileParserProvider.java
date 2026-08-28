package com.intellij.plugins.haxe.profiler.bridge.js;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.CancellableStream;
import com.intellij.plugins.haxe.profiler.bridge.data.HaxeSamplingProfilerData;
import com.intellij.plugins.haxe.profiler.js.CpuProfileTranslator;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.profiler.api.Failure;
import com.intellij.profiler.api.ProfilerDumpFileParser;
import com.intellij.profiler.api.ProfilerDumpFileParsingResult;
import com.intellij.profiler.api.ProfilerDumpParserProvider;
import com.intellij.profiler.api.Success;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Registers V8 {@code .cpuprofile} files (Chrome DevTools saves, CDP
 * {@code Profiler.stop} output — what a JS-target capture produces) with
 * the IU snapshot import. Opens as sampled data with the stock views plus
 * our Call Chart; positions point at the generated JavaScript.
 */
public class HaxeJsCpuProfileParserProvider implements ProfilerDumpParserProvider {

  @Override
  public @NotNull String getId() {
    return "haxe.cpuprofile";
  }

  @Override
  public @NotNull String getName() {
    return HaxeProfilerBundle.message("haxe.profiler.js.snapshot.name");
  }

  @Override
  public @NotNull String getRequiredFileExtension() {
    return "cpuprofile";
  }

  @Override
  public @NotNull ProfilerDumpFileParser createParser(@NotNull Project project) {
    return new CpuProfileFileParser();
  }

  private static final class CpuProfileFileParser implements ProfilerDumpFileParser {
    @Override
    public @NotNull ProfilerDumpFileParsingResult parse(@NotNull File file, @NotNull ProgressIndicator indicator) {
      try (InputStream in = new CancellableStream(new BufferedInputStream(new FileInputStream(file)), indicator)) {
        ProfilerSnapshot snapshot = CpuProfileTranslator.translate(in);
        return new Success(HaxeSamplingProfilerData.from(snapshot));
      }
      catch (IOException e) {
        return new Failure(HaxeProfilerBundle.message("haxe.profiler.parse.failed", e.getMessage()));
      }
    }

    @Override
    public @Nullable String getHelpId() {
      return null;
    }
  }
}
