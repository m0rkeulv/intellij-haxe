package com.intellij.plugins.haxe.ide.inspections.buildfiles;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileScanner;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** What the build-file inspections share: which XML files they look at and how they address attribute values. */
final class HaxeBuildFileInspections {

  private HaxeBuildFileInspections() {
  }

  /** Whether the plugin classifies the file as a Lime, OpenFL or NME project file. */
  static boolean isProjectXml(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    return virtualFile != null && HaxeBuildFileScanner.detectType(file.getProject(), virtualFile) != null;
  }

  /** The folder the file's relative paths resolve against; null for a file without one. */
  @Nullable
  static VirtualFile folderOf(@NotNull PsiFile file) {
    VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
    return virtualFile == null ? null : virtualFile.getParent();
  }

  @Nullable
  static XmlAttributeValue nameAttributeValue(@NotNull XmlTag tag) {
    XmlAttribute attribute = tag.getAttribute("name");
    return attribute == null ? null : attribute.getValueElement();
  }

  /** The value's text without its quotes, as a range inside the value element. */
  @NotNull
  static TextRange valueRange(@NotNull XmlAttributeValue value) {
    return value.getValueTextRange().shiftLeft(value.getTextRange().getStartOffset());
  }
}
