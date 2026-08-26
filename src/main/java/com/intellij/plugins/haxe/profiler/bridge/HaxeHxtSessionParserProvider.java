package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.hxt.HxtSessionTranslator;
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
 * Registers captured HXTS telemetry sessions ({@code .hxtsession}) with the
 * IU snapshot import. A session is a full sampled capture with a time axis,
 * so it opens with the complete tab set — the stock views plus our Call
 * Chart and Timeline.
 */
public class HaxeHxtSessionParserProvider implements ProfilerDumpParserProvider {

  @Override
  public @NotNull String getId() {
    return "haxe.hxtsession";
  }

  @Override
  public @NotNull String getName() {
    return HaxeProfilerBundle.message("haxe.profiler.hxt.snapshot.name");
  }

  @Override
  public @NotNull String getRequiredFileExtension() {
    return "hxtsession";
  }

  @Override
  public @NotNull ProfilerDumpFileParser createParser(@NotNull Project project) {
    return new HxtSessionFileParser();
  }

  private static final class HxtSessionFileParser implements ProfilerDumpFileParser {
    @Override
    public @NotNull ProfilerDumpFileParsingResult parse(@NotNull File file, @NotNull ProgressIndicator indicator) {
      ProfilerSnapshot snapshot;
      try (InputStream in = new CancellableStream(new BufferedInputStream(new FileInputStream(file)), indicator)) {
        snapshot = HxtSessionTranslator.translate(in);
      }
      catch (IOException e) {
        return new Failure(HaxeProfilerBundle.message("haxe.profiler.parse.failed", e.getMessage()));
      }
      return new Success(HaxeSamplingProfilerData.from(snapshot));
    }

    @Override
    public @Nullable String getHelpId() {
      return null;
    }
  }
}
