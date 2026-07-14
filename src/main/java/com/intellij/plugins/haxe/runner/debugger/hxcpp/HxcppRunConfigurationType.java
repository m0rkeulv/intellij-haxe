package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import javax.swing.Icon;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Dedicated run/debug configuration type for native HXCPP executables
 * (experimental).
 *
 * Deliberately its own type rather than a mode of the generic Haxe Application
 * configuration: the HXCPP runners key on {@link HxcppRunConfiguration}, so
 * the legacy runners (which also carry Flash/Flex debugging) never see an
 * HXCPP run and vice versa.
 */
public class HxcppRunConfigurationType implements ConfigurationType {
  private final HxcppFactory factory = new HxcppFactory(this);

  @Override
  public @NotNull String getDisplayName() {
    return HaxeBundle.message("hxcpp.runner.configuration.name");
  }

  @Override
  public String getConfigurationTypeDescription() {
    return HaxeBundle.message("hxcpp.runner.configuration.description");
  }

  @Override
  public Icon getIcon() {
    return icons.HaxeIcons.HAXE_LOGO;
  }

  @Override
  public @NotNull String getId() {
    return "HxcppRunConfiguration";
  }

  @Override
  public ConfigurationFactory[] getConfigurationFactories() {
    return new ConfigurationFactory[]{factory};
  }

  public static class HxcppFactory extends ConfigurationFactory {
    public HxcppFactory(ConfigurationType type) {
      super(type);
    }

    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
      return new HxcppRunConfiguration(HaxeBundle.message("hxcpp.runner.configuration.name"), project, this);
    }

    @Override
    public @NotNull @NonNls String getId() {
      // must not come from a localized bundle
      return "HXCPP Application";
    }
  }
}
