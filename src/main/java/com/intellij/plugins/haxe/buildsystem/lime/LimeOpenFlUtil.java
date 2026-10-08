package com.intellij.plugins.haxe.buildsystem.lime;

import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@UtilityClass
public class LimeOpenFlUtil {

  public boolean looksLikeALimeProjectFile(@NotNull XmlTag rootTag) {
    return LimeProjectShape.of(rootTag).isLimeFamily();
  }

  public boolean isOpenFlFile(@NotNull XmlFile file) {
    LimeProjectShape shape = shapeOf(file);
    return shape != null && shape.isOpenfl();
  }

  public boolean isLimeFile(@NotNull XmlFile file) {
    LimeProjectShape shape = shapeOf(file);
    return shape != null && shape.isLime();
  }

  /** The file's shape, or null when its extension or its document rules it out. */
  @Nullable
  private LimeProjectShape shapeOf(@NotNull XmlFile file) {
    if (!LimeProjectShape.hasFileExtension(file.getName())) return null;
    XmlDocument document = file.getDocument();
    XmlTag rootTag = document == null ? null : document.getRootTag();
    return rootTag == null ? null : LimeProjectShape.of(rootTag);
  }
}
