package com.intellij.plugins.haxe.profiler.bridge;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.profiler.api.AttachableTargetProcess;
import com.intellij.profiler.api.ProfilerProcess;
import com.intellij.profiler.api.configurations.ProfilerAttacher;
import com.intellij.icons.AllIcons;
import com.intellij.profiler.api.configurations.ProfilerConfigurationType;
import com.intellij.profiler.api.configurations.ProfilerStarter;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.Icon;

/**
 * The "HashLink Profiler" entry of the IU Run-with-Profiler executor. A
 * configuration of this type makes the profiler button runnable for run
 * configurations passing the {@link HaxeProfilableRunConfiguration} readiness
 * gate; its state carries the sampling rate, editable in the executor's
 * profiler-configuration settings.
 */
public class HaxeHlProfilerConfigurationType implements ProfilerConfigurationType<HaxeHlProfilerConfigurationState> {

  public static final String ID = "HaxeHashLinkProfilerConfiguration";
  private static final String SAMPLES_ATTRIBUTE = "samplesPerSecond";
  private static final String NAME_ATTRIBUTE = "name";

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return HaxeProfilerBundle.message("haxe.profiler.hl.configuration.name");
  }

  @Override
  public @NotNull Icon getIcon() {
    // the platform's profiler icon - the same one the Run with Profiler button carries
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
  public @NotNull HaxeHlProfilerConfigurationState getTemplateState() {
    return new HaxeHlProfilerConfigurationState(getDisplayName(), HaxeHlProfilerConfigurationState.DEFAULT_SAMPLES_PER_SECOND);
  }

  @Override
  public @NotNull HaxeHlProfilerConfigurationState copyState(@NotNull HaxeHlProfilerConfigurationState state) {
    return new HaxeHlProfilerConfigurationState(state.getDisplayName(), state.getSamplesPerSecond());
  }

  @Override
  public @NotNull HaxeHlProfilerConfigurationState readState(@NotNull Element element) {
    HaxeHlProfilerConfigurationState state = getTemplateState();
    String name = element.getAttributeValue(NAME_ATTRIBUTE);
    if (name != null) state.setDisplayName(name);
    try {
      state.setSamplesPerSecond(Integer.parseInt(element.getAttributeValue(SAMPLES_ATTRIBUTE, "")));
    }
    catch (NumberFormatException ignored) {
      // keep the template default
    }
    return state;
  }

  @Override
  public @NotNull Element writeState(@NotNull HaxeHlProfilerConfigurationState state) {
    Element element = new Element("haxeHashlinkProfiler");
    element.setAttribute(NAME_ATTRIBUTE, state.getDisplayName());
    element.setAttribute(SAMPLES_ATTRIBUTE, String.valueOf(state.getSamplesPerSecond()));
    return element;
  }

  @Override
  public @NotNull UnnamedConfigurable createConfigurable(@NotNull HaxeHlProfilerConfigurationState state) {
    return new HaxeHlProfilerConfigurable(state);
  }

  @Override
  public @Nullable String getHelpTopic() {
    return null;
  }

  @Override
  public @NotNull ProfilerStarter createStarter(@NotNull HaxeHlProfilerConfigurationState state) {
    return new ProfilerStarter() {
      /** Drives the run widget's profiler BUTTON: enabled only while the SELECTED configuration is profilable. */
      @Override
      public boolean isApplicable(@NotNull Project project) {
        RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
        return selected != null && canRun(selected.getConfiguration());
      }

      /** The launch-time gate, per configuration; the lane check keeps this entry off hxcpp runs. */
      @Override
      public boolean canRun(@NotNull RunProfile profile) {
        // the readiness gate keys the profiler per TARGET: false while
        // indexing and when a target switch left the configuration stale
        return profile instanceof HaxeProfilableRunConfiguration configuration
               && configuration.profilingLane() == HaxeProfilableRunConfiguration.Lane.HASHLINK
               && configuration.isProfilingReady();
      }
    };
  }

  @Override
  public @NotNull ProfilerAttacher createAttacher(@NotNull HaxeHlProfilerConfigurationState state) {
    // hashlink's sampler starts with the process (--profile); attach-to-running is not a thing
    return new ProfilerAttacher() {
      @Override
      public @NotNull Promise<ProfilerProcess<AttachableTargetProcess>> attachTo(@NotNull AttachableTargetProcess process,
                                                                                 @NotNull Project project) {
        return Promises.rejectedPromise(HaxeProfilerBundle.message("haxe.profiler.attach.unsupported"));
      }
    };
  }
}
