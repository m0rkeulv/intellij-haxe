package com.intellij.plugins.haxe.v2.buildsystem;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Lime, OpenFL and NME project files as PSI: which XML files qualify, and the folder their paths resolve against. */
public final class HaxeProjectXmlFiles {

  private HaxeProjectXmlFiles() {
  }

  /** Whether the plugin classifies the file as a Lime, OpenFL or NME project file ({@link HaxeBuildFileScanner#detectType}). */
  public static boolean isProjectXml(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    return file instanceof XmlFile && virtualFile != null && HaxeBuildFileScanner.detectType(file.getProject(), virtualFile) != null;
  }

  /** The project file's folder, which its relative paths resolve against; null for a file without one. */
  @Nullable
  public static VirtualFile folderOf(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    return virtualFile == null ? null : virtualFile.getParent();
  }
}
