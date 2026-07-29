package com.intellij.plugins.haxe.v2.buildtools.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent implementation of {@link HaxeBuildToolSettings}, stored in {@code .idea/haxeBuildTools.xml}.
 */
@State(name = "HaxeBuildToolSettings", storages = @Storage("haxeBuildTools.xml"))
public final class HaxeBuildToolProjectSettings implements HaxeBuildToolSettings, PersistentStateComponent<HaxeBuildToolProjectSettings.State> {

  public static final class State {
    public String sdkName;
    public String nekoPath = "";
    public String hashlinkPath = "";
    public String haxelibPath = "";
    public boolean compilationServerEnabled;
    public int compilationServerPort;
    public String compilationServerArguments = "";
  }

  private State state = new State();

  @Override
  public @NotNull State getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull State state) {
    this.state = state;
  }

  @Override
  public @Nullable String getSdkName() {
    return StringUtil.nullize(state.sdkName);
  }

  @Override
  public void setSdkName(@Nullable String sdkName) {
    state.sdkName = sdkName;
  }

  @Override
  public @NotNull String getNekoPath() {
    return StringUtil.notNullize(state.nekoPath);
  }

  @Override
  public void setNekoPath(@NotNull String path) {
    state.nekoPath = path;
  }

  @Override
  public @NotNull String getHashlinkPath() {
    return StringUtil.notNullize(state.hashlinkPath);
  }

  @Override
  public void setHashlinkPath(@NotNull String path) {
    state.hashlinkPath = path;
  }

  @Override
  public @NotNull String getHaxelibPath() {
    return StringUtil.notNullize(state.haxelibPath);
  }

  @Override
  public void setHaxelibPath(@NotNull String path) {
    state.haxelibPath = path;
  }

  @Override
  public boolean isCompilationServerEnabled() {
    return state.compilationServerEnabled;
  }

  @Override
  public void setCompilationServerEnabled(boolean enabled) {
    state.compilationServerEnabled = enabled;
  }

  @Override
  public int getCompilationServerPort() {
    return state.compilationServerPort;
  }

  @Override
  public void setCompilationServerPort(int port) {
    state.compilationServerPort = Math.max(port, 0);
  }

  @Override
  public @NotNull String getCompilationServerArguments() {
    return StringUtil.notNullize(state.compilationServerArguments);
  }

  @Override
  public void setCompilationServerArguments(@NotNull String arguments) {
    state.compilationServerArguments = arguments;
  }
}
