package com.intellij.plugins.haxe.v2.compiler.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent implementation of {@link HaxeCompilerSettings}, stored in {@code .idea/haxeCompiler.xml}.
 */
@State(name = "HaxeCompilerConfiguration", storages = @Storage("haxeCompiler.xml"))
public final class HaxeCompilerProjectSettings implements HaxeCompilerSettings, PersistentStateComponent<HaxeCompilerProjectSettings.State> {

  public static final class State {
    public String defaultLanguageLevel = HaxeLanguageLevel.latest().getVersionString();
    public Map<String, String> moduleLanguageLevels = new TreeMap<>();
    public boolean compilerDiagnostics = false;
  }

  private State state = new State();

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    if (state.moduleLanguageLevels == null) {
      state.moduleLanguageLevels = new TreeMap<>();
    }
    this.state = state;
  }

  @Override
  public @NotNull HaxeLanguageLevel getDefaultLanguageLevel() {
    HaxeLanguageLevel level = HaxeLanguageLevel.fromVersionString(state.defaultLanguageLevel);
    return level != null ? level : HaxeLanguageLevel.latest();
  }

  @Override
  public void setDefaultLanguageLevel(@NotNull HaxeLanguageLevel level) {
    state.defaultLanguageLevel = level.getVersionString();
  }

  @Override
  public @NotNull Map<String, HaxeLanguageLevel> getModuleLanguageLevelOverrides() {
    Map<String, HaxeLanguageLevel> result = new LinkedHashMap<>();
    state.moduleLanguageLevels.forEach((moduleName, version) -> {
      HaxeLanguageLevel level = HaxeLanguageLevel.fromVersionString(version);
      if (level != null) {
        result.put(moduleName, level);
      }
    });
    return result;
  }

  @Override
  public void setModuleLanguageLevelOverrides(@NotNull Map<String, HaxeLanguageLevel> overrides) {
    Map<String, String> serialized = new TreeMap<>();
    overrides.forEach((moduleName, level) -> serialized.put(moduleName, level.getVersionString()));
    state.moduleLanguageLevels = serialized;
  }

  @Override
  public @Nullable HaxeLanguageLevel getModuleLanguageLevelOverride(@NotNull String moduleName) {
    return HaxeLanguageLevel.fromVersionString(state.moduleLanguageLevels.get(moduleName));
  }

  @Override
  public void setModuleLanguageLevelOverride(@NotNull String moduleName, @Nullable HaxeLanguageLevel level) {
    if (level == null) {
      state.moduleLanguageLevels.remove(moduleName);
    }
    else {
      state.moduleLanguageLevels.put(moduleName, level.getVersionString());
    }
  }

  @Override
  public @NotNull HaxeLanguageLevel getEffectiveLanguageLevel(@NotNull String moduleName) {
    HaxeLanguageLevel override = getModuleLanguageLevelOverride(moduleName);
    return override != null ? override : getDefaultLanguageLevel();
  }

  @Override
  public boolean isCompilerDiagnosticsEnabled() {
    return state.compilerDiagnostics;
  }

  @Override
  public void setCompilerDiagnosticsEnabled(boolean enabled) {
    state.compilerDiagnostics = enabled;
  }
}
