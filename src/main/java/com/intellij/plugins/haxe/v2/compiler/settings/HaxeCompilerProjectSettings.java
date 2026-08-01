package com.intellij.plugins.haxe.v2.compiler.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevel;
import com.intellij.plugins.haxe.v2.compiler.HaxeLanguageLevelUtil;
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

  /** Stored in place of a version to select "use compiler level" mode. */
  static final String USE_COMPILER_LEVEL = "auto";

  public static final class State {
    public String defaultLanguageLevel = USE_COMPILER_LEVEL;
    public Map<String, String> moduleLanguageLevels = new TreeMap<>();
    public boolean compilerDiagnostics = false;
    public String completionMode = HaxeCompletionMode.IDE_AND_COMPILER.getId();
  }

  private final @Nullable Project project;
  private State state = new State();

  /** Null project (tests): "use compiler level" cannot resolve an SDK and falls back to the latest level. */
  public HaxeCompilerProjectSettings(@Nullable Project project) {
    this.project = project;
  }

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
    return defaultLevelFor(null);
  }

  @Override
  public @NotNull HaxeLanguageLevel getDefaultLanguageLevel(@NotNull String moduleName) {
    return defaultLevelFor(moduleName);
  }

  @Override
  public @Nullable HaxeLanguageLevel getExplicitDefaultLanguageLevel() {
    return HaxeLanguageLevel.fromVersionString(state.defaultLanguageLevel);
  }

  @Override
  public void setDefaultLanguageLevel(@Nullable HaxeLanguageLevel level) {
    state.defaultLanguageLevel = level == null ? USE_COMPILER_LEVEL : level.getVersionString();
  }

  // In "use compiler level" mode (also the fallback for unparsable stored
  // values) the container's SDK decides; latest() when no SDK is registered.
  @NotNull
  private HaxeLanguageLevel defaultLevelFor(@Nullable String moduleName) {
    HaxeLanguageLevel explicit = getExplicitDefaultLanguageLevel();
    if (explicit != null) return explicit;
    HaxeLanguageLevel fromCompiler = project == null ? null : HaxeLanguageLevelUtil.fromCompiler(project, moduleName);
    return fromCompiler != null ? fromCompiler : HaxeLanguageLevel.latest();
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
    return override != null ? override : defaultLevelFor(moduleName);
  }

  @Override
  public boolean isCompilerDiagnosticsEnabled() {
    return state.compilerDiagnostics;
  }

  @Override
  public void setCompilerDiagnosticsEnabled(boolean enabled) {
    state.compilerDiagnostics = enabled;
  }

  @Override
  public @NotNull HaxeCompletionMode getCompletionMode() {
    return HaxeCompletionMode.fromId(state.completionMode);
  }

  @Override
  public void setCompletionMode(@NotNull HaxeCompletionMode mode) {
    state.completionMode = mode.getId();
  }
}
