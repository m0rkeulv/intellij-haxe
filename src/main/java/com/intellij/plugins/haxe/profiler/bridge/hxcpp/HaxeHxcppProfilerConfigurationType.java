package com.intellij.plugins.haxe.profiler.bridge.hxcpp;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration.Lane;
import com.intellij.profiler.api.AttachableTargetProcess;
import com.intellij.profiler.api.ProfilerProcess;
import com.intellij.profiler.api.configurations.ProfilerAttacher;
import com.intellij.profiler.api.configurations.ProfilerConfigurationType;
import com.intellij.profiler.api.configurations.ProfilerStarter;
import com.intellij.ui.components.JBLabel;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * The "hxcpp Profiler" entry of the IU Run-with-Profiler executor: builds the
 * program with hxcpp's built-in sampler compiled in (an injected bootstrap
 * starts/stops it around main). No settings — the sampler ticks at a fixed
 * 1 ms.
 */
public class HaxeHxcppProfilerConfigurationType implements ProfilerConfigurationType<HaxeHxcppProfilerConfigurationState> {

  public static final String ID = "HaxeHxcppProfilerConfiguration";
  private static final String NAME_ATTRIBUTE = "name";

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return HaxeProfilerBundle.message("haxe.profiler.hxcpp.configuration.name");
  }

  @Override
  public @NotNull Icon getIcon() {
    return AllIcons.Actions.Profile;
  }

  @Override
  public @NotNull String getLanguageSettingsGroup() {
    return HaxeProfilerBundle.message("haxe.profiler.settings.group");
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public @NotNull HaxeHxcppProfilerConfigurationState getTemplateState() {
    return new HaxeHxcppProfilerConfigurationState(getDisplayName());
  }

  @Override
  public @NotNull HaxeHxcppProfilerConfigurationState copyState(@NotNull HaxeHxcppProfilerConfigurationState state) {
    return new HaxeHxcppProfilerConfigurationState(state.getDisplayName());
  }

  @Override
  public @NotNull HaxeHxcppProfilerConfigurationState readState(@NotNull Element element) {
    HaxeHxcppProfilerConfigurationState state = getTemplateState();
    String name = element.getAttributeValue(NAME_ATTRIBUTE);
    if (name != null) state.setDisplayName(name);
    return state;
  }

  @Override
  public @NotNull Element writeState(@NotNull HaxeHxcppProfilerConfigurationState state) {
    Element element = new Element("haxeHxcppProfiler");
    element.setAttribute(NAME_ATTRIBUTE, state.getDisplayName());
    return element;
  }

  @Override
  public @NotNull UnnamedConfigurable createConfigurable(@NotNull HaxeHxcppProfilerConfigurationState state) {
    return new UnnamedConfigurable() {
      @Override
      public @Nullable JComponent createComponent() {
        return new JBLabel(HaxeProfilerBundle.message("haxe.profiler.hxcpp.settings.fixed.rate"));
      }

      @Override
      public boolean isModified() {
        return false;
      }

      @Override
      public void apply() {
      }
    };
  }

  @Override
  public @Nullable String getHelpTopic() {
    return null;
  }

  @Override
  public @NotNull ProfilerStarter createStarter(@NotNull HaxeHxcppProfilerConfigurationState state) {
    return new ProfilerStarter() {
      /** Drives the run widget's profiler BUTTON: enabled only while the SELECTED configuration is hxcpp-profilable. */
      @Override
      public boolean isApplicable(@NotNull Project project) {
        RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
        return selected != null && canRun(selected.getConfiguration());
      }

      /** The launch-time gate, per configuration; the lane check keeps this entry off HashLink runs. */
      @Override
      public boolean canRun(@NotNull RunProfile profile) {
        return profile instanceof HaxeProfilableRunConfiguration configuration
               && configuration.profilingLane() == Lane.HXCPP
               && configuration.isProfilingReady();
      }
    };
  }

  @Override
  public @NotNull ProfilerAttacher createAttacher(@NotNull HaxeHxcppProfilerConfigurationState state) {
    // the sampler is compiled into the binary and started around main; attach-to-running is not a thing
    return new ProfilerAttacher() {
      @Override
      public @NotNull Promise<ProfilerProcess<AttachableTargetProcess>> attachTo(@NotNull AttachableTargetProcess process,
                                                                                 @NotNull Project project) {
        return Promises.rejectedPromise(HaxeProfilerBundle.message("haxe.profiler.attach.unsupported"));
      }
    };
  }
}
