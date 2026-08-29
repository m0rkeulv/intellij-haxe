package com.intellij.plugins.haxe.v2.display;

import com.intellij.codeInsight.daemon.impl.analysis.DefaultHighlightingSettingProvider;
import com.intellij.codeInsight.daemon.impl.analysis.FileHighlightingSetting;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Generated-code previews run at the "Syntax" highlighting level: annotators
 * still run (the color annotators paint the preview; the semantic ones
 * self-skip preview files, see {@code AnnotatorUtil.isInGeneratedPreview}),
 * while inspections and external annotators — analysis that could only
 * report noise on a reconstruction — are skipped wholesale.
 */
public class HaxeGeneratedPreviewHighlightingSetting extends DefaultHighlightingSettingProvider {

  @Override
  @Nullable
  public FileHighlightingSetting getDefaultSetting(@NotNull Project project, @NotNull VirtualFile file) {
    return file.getUserData(HaxeGeneratedCodePreview.PREVIEW_KEY) != null ? FileHighlightingSetting.SKIP_INSPECTION : null;
  }
}
