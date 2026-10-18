package com.intellij.plugins.haxe.v2.buildtools.classpaths;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.Collection;
import java.util.List;

/** One external classpath folder outside every module, shown under External Libraries. */
final class HaxeExternalClasspathLibrary extends SyntheticLibrary implements ItemPresentation {

  private final VirtualFile directory;

  HaxeExternalClasspathLibrary(@NotNull VirtualFile directory) {
    super("haxe-classpath:" + directory.getPath(), null);
    this.directory = directory;
  }

  @Override
  public @NotNull Collection<VirtualFile> getSourceRoots() {
    return List.of(directory);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof HaxeExternalClasspathLibrary library && directory.equals(library.directory);
  }

  @Override
  public int hashCode() {
    return directory.hashCode();
  }

  @Override
  public @NotNull String getPresentableText() {
    return HaxeBundle.message("haxe.library.external.classpath", directory.getPresentableUrl());
  }

  @Override
  public @NotNull Icon getIcon(boolean unused) {
    return AllIcons.Nodes.PpLibFolder;
  }
}
