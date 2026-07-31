package com.intellij.plugins.haxe.buildsystem.hxp;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * HXP project scripts (lime/hxp build files). The content IS Haxe source (a
 * {@code Project} class evaluated by the hxp tool), so the file type binds to
 * the Haxe language — registered as SECONDARY so .hx stays the language's
 * primary type. A distinct type keeps its own icon and lets features treat
 * project scripts differently from regular sources where needed.
 */
public final class HxpFileType extends LanguageFileType {
  public static final HxpFileType INSTANCE = new HxpFileType();

  @NonNls
  public static final String DEFAULT_EXTENSION = "hxp";

  private HxpFileType() {
    super(HaxeLanguage.INSTANCE, true);
  }

  @Override
  @NotNull
  @NonNls
  public String getName() {
    return "HXP";
  }

  @Override
  @NotNull
  public String getDescription() {
    return HaxeBundle.message("hxp.file.type.description");
  }

  @Override
  @NotNull
  @NonNls
  public String getDefaultExtension() {
    return DEFAULT_EXTENSION;
  }

  @Override
  public Icon getIcon() {
    return icons.HaxeIcons.LIME_LOGO;
  }

  @Override
  public String getCharset(@NotNull VirtualFile file, byte[] content) {
    return CharsetToolkit.UTF8;
  }
}
