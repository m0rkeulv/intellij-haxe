package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableProvider;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.profiler.configurations.ui.EditProfilerConfigurationsComponent;
import org.jetbrains.annotations.Nullable;

/**
 * The "Haxe Profiler" page under Settings | Build, Execution, Deployment |
 * Profiler: the platform's stock configurations editor filtered to our
 * language group — every language ships its own page this way (the "Java
 * Profiler" page filters to the JVM types' group key and never lists
 * foreign types). Renders each Haxe configuration's own form: the HashLink
 * sampling rate, the hxcpp Tracy compression level, the hxcpp note.
 */
public class HaxeProfilerConfigurableProvider extends ConfigurableProvider {

  @Override
  public @Nullable Configurable createConfigurable() {
    // the first argument must equal our configuration types' getLanguageSettingsGroup
    String languageGroup = HaxeProfilerBundle.message("haxe.profiler.settings.group");
    return new EditProfilerConfigurationsComponent(languageGroup, "procedures.profiler");
  }
}
