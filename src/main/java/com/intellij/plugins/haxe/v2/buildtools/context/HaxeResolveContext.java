package com.intellij.plugins.haxe.v2.buildtools.context;

import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFile;
import org.jetbrains.annotations.NotNull;

/**
 * The build a file is analysed under: one of the project's known build
 * files, taken with the target or section that build is currently set to
 * (those selections live per build file in their own stores). A context
 * names the build only; the define set it implies comes later.
 */
// TODO: multiverse: becomes the plugin's CodeInsightContext implementation - see doc/planned/resolve-context-plan.md, route B
public record HaxeResolveContext(@NotNull HaxeBuildFile buildFile) {

  /** The build file's path, the key every per-build-file store uses. */
  @NotNull
  public String path() {
    return buildFile.file().getPath();
  }
}
