package com.intellij.plugins.haxe.profiler.bridge.hxcpp;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.CancellableStream;
import com.intellij.plugins.haxe.profiler.bridge.data.HaxeHxcppProfilerData;
import com.intellij.plugins.haxe.profiler.hxcpp.HxcppProfileReport;
import com.intellij.plugins.haxe.profiler.hxcpp.HxcppProfileTranslator;
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
 * Registers hxcpp profiler reports with the IU snapshot import. The report
 * ({@code cpp.vm.Profiler.start/stop} in a {@code -debug -D HXCPP_PROFILER}
 * build) is headerless text, so routing is by the {@code .hxcppprof}
 * extension — the file name to pass to {@code Profiler.start()}; a foreign
 * text file fails the parse as a Failure with the offending line.
 */
public class HaxeHxcppDumpParserProvider implements ProfilerDumpParserProvider {

  @Override
  public @NotNull String getId() {
    return "haxe.hxcpp";
  }

  @Override
  public @NotNull String getName() {
    return HaxeProfilerBundle.message("haxe.profiler.hxcpp.snapshot.name");
  }

  @Override
  public @NotNull String getRequiredFileExtension() {
    return "hxcppprof";
  }

  @Override
  public @NotNull ProfilerDumpFileParser createParser(@NotNull Project project) {
    return new HxcppReportFileParser();
  }

  private static final class HxcppReportFileParser implements ProfilerDumpFileParser {
    @Override
    public @NotNull ProfilerDumpFileParsingResult parse(@NotNull File file, @NotNull ProgressIndicator indicator) {
      HxcppProfileReport report;
      try (InputStream in = new CancellableStream(new BufferedInputStream(new FileInputStream(file)), indicator)) {
        report = HxcppProfileTranslator.translate(in);
      }
      catch (IOException e) {
        return new Failure(HaxeProfilerBundle.message("haxe.profiler.parse.failed", e.getMessage()));
      }
      return new Success(HaxeHxcppProfilerData.from(report));
    }

    @Override
    public @Nullable String getHelpId() {
      return null;
    }
  }
}
