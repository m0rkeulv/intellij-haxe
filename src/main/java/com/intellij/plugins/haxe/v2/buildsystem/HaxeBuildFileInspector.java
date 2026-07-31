package com.intellij.plugins.haxe.v2.buildsystem;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Reads a build file's declared target, defines and library dependencies.
 * Call inside a read action. HXP files cannot be inspected statically —
 * they need `lime display` (planned) and report empty info for now.
 */
@CustomLog
public final class HaxeBuildFileInspector {

  private HaxeBuildFileInspector() {
  }

  @NotNull
  public static HaxeBuildFileInfo inspect(@NotNull HaxeBuildFile buildFile) {
    String content = loadText(buildFile.file());
    if (content == null) return HaxeBuildFileInfo.EMPTY;

    return switch (buildFile.type()) {
      case HXML -> HxmlFileParser.parse(content, path -> resolveInclude(buildFile.file(), path));
      case OPENFL, LIME, NMML -> ProjectXmlParser.parse(content);
      case HXP_PROJECT, HXP_SCRIPT -> HaxeBuildFileInfo.EMPTY;
    };
  }

  @Nullable
  private static String resolveInclude(@NotNull VirtualFile origin, @NotNull String relativePath) {
    VirtualFile parent = origin.getParent();
    if (parent == null) return null;
    VirtualFile included = parent.findFileByRelativePath(relativePath);
    return included == null ? null : loadText(included);
  }

  @Nullable
  public static String loadText(@NotNull VirtualFile file) {
    // An open editor's unsaved changes live in the Document, not the VFS - prefer it
    // so a tree refresh reflects what the user sees without requiring a save.
    Document document = FileDocumentManager.getInstance().getCachedDocument(file);
    if (document != null) {
      return document.getText();
    }
    try {
      return VfsUtilCore.loadText(file);
    }
    catch (IOException e) {
      log.debug("Unable to read build file " + file.getPath());
      return null;
    }
  }
}
