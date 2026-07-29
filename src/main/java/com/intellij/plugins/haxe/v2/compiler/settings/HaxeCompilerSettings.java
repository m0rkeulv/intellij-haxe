package com.intellij.plugins.haxe.v2.compiler.settings;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Project-level Haxe compiler configuration (v2).
 * <p>
 * Modules are identified by name so that the settings can be read and tested
 * without a live {@link Module} instance.
 */
public interface HaxeCompilerSettings {

  @NotNull
  static HaxeCompilerSettings getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerSettings.class);
  }

  /** The language level used by every module without an explicit override. */
  @NotNull
  HaxeLanguageLevel getDefaultLanguageLevel();

  void setDefaultLanguageLevel(@NotNull HaxeLanguageLevel level);

  /** Explicit per-module overrides, keyed by module name. Modules without an entry use the default. */
  @NotNull
  Map<String, HaxeLanguageLevel> getModuleLanguageLevelOverrides();

  void setModuleLanguageLevelOverrides(@NotNull Map<String, HaxeLanguageLevel> overrides);

  @Nullable
  HaxeLanguageLevel getModuleLanguageLevelOverride(@NotNull String moduleName);

  /** Sets or clears (when {@code level} is null) the override for a single module. */
  void setModuleLanguageLevelOverride(@NotNull String moduleName, @Nullable HaxeLanguageLevel level);

  @NotNull
  HaxeLanguageLevel getEffectiveLanguageLevel(@NotNull String moduleName);

  @NotNull
  default HaxeLanguageLevel getEffectiveLanguageLevel(@NotNull Module module) {
    return getEffectiveLanguageLevel(module.getName());
  }
}
