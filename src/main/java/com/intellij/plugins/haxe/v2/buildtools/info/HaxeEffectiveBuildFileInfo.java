package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo.HaxeLibDependency;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildSections;
import com.intellij.plugins.haxe.v2.buildtools.HaxeContainers;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A build file's info as the build sees it, shared by every reader (the
 * tool window, the define context, the library sync): the selected section
 * of an hxml; an nmml per the {@code nme prepare} evaluation; a lime-family
 * file per the lime evaluation of the selected target, with the declared
 * libraries kept when the evaluation carries none (the legacy display path
 * lists no libraries). Until an evaluation lands - or without one: untrusted
 * project, failed tool - the raw parse serves with the selected target's
 * platform defines, its conditions unevaluated. Call in a read action; the
 * landing is announced as
 * {@link com.intellij.plugins.haxe.v2.buildtools.HaxeBuildEventListener#evaluationLanded}.
 */
public final class HaxeEffectiveBuildFileInfo {
  private HaxeEffectiveBuildFileInfo() {
  }

  @NotNull
  public static HaxeBuildFileInfo effectiveInfo(@NotNull Project project, @NotNull HaxeBuildFile buildFile) {
    return effectiveInfo(project, buildFile, HaxeProjectInfoCache.NO_CALLBACK);
  }

  /** {@link #effectiveInfo(Project, HaxeBuildFile)} for a caller that must act on its own ask once the evaluation lands; kept by identity. */
  @NotNull
  public static HaxeBuildFileInfo effectiveInfo(@NotNull Project project, @NotNull HaxeBuildFile buildFile, @NotNull Runnable onEvaluated) {
    HaxeBuildFileInfo raw = HaxeBuildSections.inspectSelected(project, buildFile);
    HaxeBuildFileType type = buildFile.type();
    String containerId = HaxeContainers.containerIdFor(project, buildFile.file());
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    if (type == HaxeBuildFileType.NMML) {
      return HaxeNmeProjectInfoService.getInstance(project).effectiveInfo(buildFile, raw, environmentSdk, onEvaluated);
    }
    if (!LimeProjects.isLimeFamily(type)) return raw;

    String targetFlag = LimeProjects.selectedTargetFlag(project, type, buildFile.file());
    HaxeBuildFileInfo evaluated = HaxeLimeProjectInfoService.getInstance(project)
      .getCachedOrSchedule(buildFile, targetFlag, environmentSdk, onEvaluated);
    if (evaluated == null) return raw.withTarget(LimeProjects.targetFor(targetFlag), null);
    List<HaxeLibDependency> libraries = !evaluated.libraries().isEmpty() ? evaluated.libraries() : raw.libraries();
    return evaluated.withLibraries(libraries);
  }
}
