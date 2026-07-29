package com.intellij.plugins.haxe.v2.compiler.settings.ui;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.compiler.settings.HaxeCompilerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;

/**
 * Settings page under Build, Execution, Deployment | Compiler | Haxe Compiler.
 */
public final class HaxeCompilerConfigurable implements SearchableConfigurable, Configurable.NoScroll {

  public static final String ID = "settings.haxe.compiler";

  private final Project project;
  private HaxeCompilerSettingsPanel panel;

  public HaxeCompilerConfigurable(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public String getDisplayName() {
    return HaxeBundle.message("haxe.compiler.configurable.name");
  }

  @Override
  public @Nullable JComponent createComponent() {
    if (panel == null) {
      panel = new HaxeCompilerSettingsPanel();
    }
    reset();
    return panel.getComponent();
  }

  @Override
  public boolean isModified() {
    if (panel == null) return false;
    HaxeCompilerSettings settings = getSettings();
    return panel.getDefaultLanguageLevel() != settings.getDefaultLanguageLevel()
           || !panel.getModuleOverrides().equals(settings.getModuleLanguageLevelOverrides());
  }

  @Override
  public void apply() {
    if (panel == null) return;
    HaxeCompilerSettings settings = getSettings();
    settings.setDefaultLanguageLevel(panel.getDefaultLanguageLevel());
    settings.setModuleLanguageLevelOverrides(panel.getModuleOverrides());
  }

  @Override
  public void reset() {
    if (panel == null) return;
    HaxeCompilerSettings settings = getSettings();
    panel.reset(settings.getDefaultLanguageLevel(), settings.getModuleLanguageLevelOverrides(), getModuleNames());
  }

  @Override
  public void disposeUIResources() {
    panel = null;
  }

  @NotNull
  private HaxeCompilerSettings getSettings() {
    return HaxeCompilerSettings.getInstance(project);
  }

  @NotNull
  private List<String> getModuleNames() {
    return Arrays.stream(ModuleManager.getInstance(project).getModules())
      .map(Module::getName)
      .sorted(String.CASE_INSENSITIVE_ORDER)
      .toList();
  }
}
