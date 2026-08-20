package com.intellij.plugins.haxe.v2.compiler.settings.ui;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeBuildConfigListener;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
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
    return panel.getSelectedDefaultLevel() != settings.getExplicitDefaultLanguageLevel()
           || !panel.getModuleOverrides().equals(settings.getModuleLanguageLevelOverrides())
           || panel.isCompilerDiagnosticsEnabled() != settings.isCompilerDiagnosticsEnabled()
           || panel.isDiagnosticsErrorsEnabled() != settings.isDiagnosticsErrorsEnabled()
           || panel.isDiagnosticsUnusedImportsEnabled() != settings.isDiagnosticsUnusedImportsEnabled()
           || panel.isDiagnosticsRemovableCodeEnabled() != settings.isDiagnosticsRemovableCodeEnabled()
           || panel.getCompletionMode() != settings.getCompletionMode();
  }

  @Override
  public void apply() {
    if (panel == null) return;
    HaxeCompilerSettings settings = getSettings();
    settings.setDefaultLanguageLevel(panel.getSelectedDefaultLevel());
    settings.setModuleLanguageLevelOverrides(panel.getModuleOverrides());
    settings.setCompilerDiagnosticsEnabled(panel.isCompilerDiagnosticsEnabled());
    settings.setDiagnosticsErrorsEnabled(panel.isDiagnosticsErrorsEnabled());
    settings.setDiagnosticsUnusedImportsEnabled(panel.isDiagnosticsUnusedImportsEnabled());
    settings.setDiagnosticsRemovableCodeEnabled(panel.isDiagnosticsRemovableCodeEnabled());
    settings.setCompletionMode(panel.getCompletionMode());
    // the tool window's Language level rows mirror these settings
    project.getMessageBus().syncPublisher(HaxeBuildConfigListener.TOPIC).buildConfigurationChanged();
    DaemonCodeAnalyzer.getInstance(project).restart("haxe: language level changed");
  }

  @Override
  public void reset() {
    if (panel == null) return;
    HaxeCompilerSettings settings = getSettings();
    panel.reset(settings.getExplicitDefaultLanguageLevel(),
                HaxeLanguageLevelUtil.fromCompiler(project, null),
                settings.getModuleLanguageLevelOverrides(),
                getModuleNames());
    panel.setCompilerDiagnosticsEnabled(settings.isCompilerDiagnosticsEnabled());
    panel.setDiagnosticsErrorsEnabled(settings.isDiagnosticsErrorsEnabled());
    panel.setDiagnosticsUnusedImportsEnabled(settings.isDiagnosticsUnusedImportsEnabled());
    panel.setDiagnosticsRemovableCodeEnabled(settings.isDiagnosticsRemovableCodeEnabled());
    panel.setCompletionMode(settings.getCompletionMode());
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
