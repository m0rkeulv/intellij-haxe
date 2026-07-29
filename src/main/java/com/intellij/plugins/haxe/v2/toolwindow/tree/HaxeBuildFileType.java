package com.intellij.plugins.haxe.v2.toolwindow.tree;

import icons.HaxeIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * The kinds of Haxe build/project files shown in the Haxe tool window tree.
 */
public enum HaxeBuildFileType {
  HXML("HXML", HaxeIcons.HAXE_LOGO),
  OPENFL("OpenFL", HaxeIcons.OPENFL_LOGO),
  LIME("Lime", HaxeIcons.LIME_LOGO),
  NMML("NME", HaxeIcons.NMML_LOGO),
  HXP("HXP", HaxeIcons.LIME_LOGO);

  private final String displayName;
  private final Icon icon;

  HaxeBuildFileType(@NotNull String displayName, @NotNull Icon icon) {
    this.displayName = displayName;
    this.icon = icon;
  }

  @NotNull
  public String getDisplayName() {
    return displayName;
  }

  @NotNull
  public Icon getIcon() {
    return icon;
  }
}
