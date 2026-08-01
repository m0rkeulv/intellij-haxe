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

  /**
   * The effective project-wide default level: the explicit choice, or in
   * "use compiler level" mode the level resolved from the configured SDK
   * (latest known level when no SDK is registered).
   */
  @NotNull
  HaxeLanguageLevel getDefaultLanguageLevel();

  /**
   * The effective default for one container: same as {@link #getDefaultLanguageLevel()},
   * except that "use compiler level" mode resolves the CONTAINER's SDK, so
   * containers with different Environment SDKs get different levels.
   */
  @NotNull
  HaxeLanguageLevel getDefaultLanguageLevel(@NotNull String moduleName);

  /** The explicitly chosen default level, or null in "use compiler level" mode. */
  @Nullable
  HaxeLanguageLevel getExplicitDefaultLanguageLevel();

  /** Null selects "use compiler level" mode (the default for new projects). */
  void setDefaultLanguageLevel(@Nullable HaxeLanguageLevel level);

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

  /**
   * Whether editor problems come from the compilation server's
   * {@code display/diagnostics} (experimental) instead of only the plugin's
   * own static analysis.
   */
  boolean isCompilerDiagnosticsEnabled();

  void setCompilerDiagnosticsEnabled(boolean enabled);

  /** Where completion and resolve get their symbols; see {@link HaxeCompletionMode}. */
  @NotNull
  HaxeCompletionMode getCompletionMode();

  void setCompletionMode(@NotNull HaxeCompletionMode mode);
}
