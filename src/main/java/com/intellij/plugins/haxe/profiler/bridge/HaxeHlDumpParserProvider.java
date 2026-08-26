package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.hl.HlProfDumpTranslator;
import com.intellij.plugins.haxe.profiler.model.ProfilerSnapshot;
import com.intellij.profiler.api.Failure;
import com.intellij.profiler.api.ProfilerDumpFileParser;
import com.intellij.profiler.api.ProfilerDumpFileParsingResult;
import com.intellij.profiler.api.SignatureBasedProfilerDumpParserProvider;
import com.intellij.profiler.api.Success;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Registers HashLink's {@code hlprofile.dump} with the IU profiler's snapshot
 * import ("Open Profiler Snapshot" and drag-and-drop). The dump's {@code .dump}
 * extension is generic, so the PROF magic doubles as the file signature and a
 * foreign file parses to a Failure instead of an error.
 */
public class HaxeHlDumpParserProvider implements SignatureBasedProfilerDumpParserProvider {

  @Override
  public @NotNull String getId() {
    return "haxe.hashlink";
  }

  @Override
  public @NotNull String getName() {
    return HaxeProfilerBundle.message("haxe.profiler.hl.snapshot.name");
  }

  @Override
  public @NotNull String getRequiredFileExtension() {
    return "dump";
  }

  @Override
  public @NotNull List<byte[]> getSupportedFileSignatures() {
    return List.of("PROF".getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public @NotNull ProfilerDumpFileParser createParser(@NotNull Project project) {
    return new HlDumpFileParser();
  }

  private static final class HlDumpFileParser implements ProfilerDumpFileParser {
    @Override
    public @NotNull ProfilerDumpFileParsingResult parse(@NotNull File file, @NotNull ProgressIndicator indicator) {
      ProfilerSnapshot snapshot;
      try (InputStream in = new CancellableStream(new BufferedInputStream(new FileInputStream(file)), indicator)) {
        snapshot = HlProfDumpTranslator.translate(in);
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
