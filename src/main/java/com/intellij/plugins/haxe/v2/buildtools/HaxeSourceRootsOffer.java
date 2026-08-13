package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileType;
import org.jetbrains.annotations.NotNull;

/**
 * After a build file is registered into a module that still has NO source
 * roots, offers to derive them from the file: resolvable classpath
 * directories become source roots, output locations become excluded. Same
 * derivation the silent first-open pass uses ({@link
 * HaxeSourceRootsInitializer}); here the user decides. Call on the EDT.
 */
public final class HaxeSourceRootsOffer {

  private HaxeSourceRootsOffer() {
  }

  public static void offerFor(@NotNull Project project, @NotNull String containerId, @NotNull VirtualFile file) {
    boolean moduleWithoutRoots = ReadAction.computeBlocking(
      () -> HaxeSourceRootsInitializer.INSTANCE.moduleHasNoSourceRoots(project, containerId));
    if (!moduleWithoutRoots) return;

    int answer = Messages.showYesNoDialog(project,
                                          HaxeBundle.message("haxe.roots.offer.message", file.getName()),
                                          HaxeBundle.message("haxe.roots.offer.title"),
                                          Messages.getQuestionIcon());
    if (answer != Messages.YES) return;

    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    if (type != null) {
      HaxeSourceRootsApplier.getInstance(project).applyFromBuildFileAsync(containerId, new HaxeBuildFile(file, type));
    }
  }
}
