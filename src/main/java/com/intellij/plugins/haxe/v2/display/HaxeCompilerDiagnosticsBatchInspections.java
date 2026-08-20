package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection;
import org.jetbrains.annotations.NotNull;

/**
 * Batch identities for the compiler-diagnostics external annotators. Pairing
 * ({@link com.intellij.lang.annotation.ExternalAnnotator#getPairedBatchInspectionShortName})
 * does two things: Inspect Code runs the annotator at all (unpaired external
 * annotators are skipped in batch mode) and its findings land under the Haxe
 * inspection group instead of the catch-all "General &gt; Annotator" node.
 * The compiler-settings toggles stay the primary gate — a disabled feature
 * collects nothing in batch just as on-the-fly.
 */
public final class HaxeCompilerDiagnosticsBatchInspections {

  private HaxeCompilerDiagnosticsBatchInspections() {
  }

  public static final String ERRORS_SHORT_NAME = "HaxeCompilerDiagnostics";
  public static final String UNUSED_IMPORT_SHORT_NAME = "HaxeCompilerUnusedImport";
  public static final String REMOVABLE_CODE_SHORT_NAME = "HaxeCompilerRemovableCode";

  public static class Errors extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    @Override
    public @NotNull String getShortName() {
      return ERRORS_SHORT_NAME;
    }
  }

  public static class UnusedImport extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    @Override
    public @NotNull String getShortName() {
      return UNUSED_IMPORT_SHORT_NAME;
    }
  }

  public static class RemovableCode extends LocalInspectionTool implements ExternalAnnotatorBatchInspection {
    @Override
    public @NotNull String getShortName() {
      return REMOVABLE_CODE_SHORT_NAME;
    }
  }
}
