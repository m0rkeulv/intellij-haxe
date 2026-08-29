package com.intellij.plugins.haxe.profiler.bridge.js;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration.Lane;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.profiler.api.AttachableTargetProcess;
import com.intellij.profiler.api.ProfilerProcess;
import com.intellij.profiler.api.configurations.ProfilerAttacher;
import com.intellij.profiler.api.configurations.ProfilerConfigurationType;
import com.intellij.profiler.api.configurations.ProfilerStarter;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.Icon;

/**
 * The "JavaScript Profiler" entry of the IU Run-with-Profiler executor: a
 * browser launch with a Chromium the IDE started itself (DevTools port
 * open), V8's sampling profiler driven over CDP in one-second stop/start
 * segments streamed into the session file — the live view follows, and a
 * browser closed by hand keeps what was collected. Chromium-family only —
 * Firefox speaks no CDP.
 */
public class HaxeJsProfilerConfigurationType implements ProfilerConfigurationType<HaxeJsProfilerConfigurationState> {

  public static final String ID = "HaxeJsProfilerConfiguration";
  private static final String NAME_ATTRIBUTE = "name";
  private static final String SAMPLING_INTERVAL_ATTRIBUTE = "samplingIntervalUs";

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return HaxeProfilerBundle.message("haxe.profiler.js.configuration.name");
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
  public @NotNull HaxeJsProfilerConfigurationState getTemplateState() {
    return new HaxeJsProfilerConfigurationState(getDisplayName());
  }

  @Override
  public @NotNull HaxeJsProfilerConfigurationState copyState(@NotNull HaxeJsProfilerConfigurationState state) {
    HaxeJsProfilerConfigurationState copy = new HaxeJsProfilerConfigurationState(state.getDisplayName());
    copy.setSamplingIntervalUs(state.getSamplingIntervalUs());
    return copy;
  }

  @Override
  public @NotNull HaxeJsProfilerConfigurationState readState(@NotNull Element element) {
    HaxeJsProfilerConfigurationState state = getTemplateState();
    String name = element.getAttributeValue(NAME_ATTRIBUTE);
    if (name != null) state.setDisplayName(name);
    try {
      state.setSamplingIntervalUs(Integer.parseInt(element.getAttributeValue(SAMPLING_INTERVAL_ATTRIBUTE, "")));
    }
    catch (NumberFormatException ignored) {
      // keep the template default
    }
    return state;
  }

  @Override
  public @NotNull Element writeState(@NotNull HaxeJsProfilerConfigurationState state) {
    Element element = new Element("haxeJsProfiler");
    element.setAttribute(NAME_ATTRIBUTE, state.getDisplayName());
    element.setAttribute(SAMPLING_INTERVAL_ATTRIBUTE, String.valueOf(state.getSamplingIntervalUs()));
    return element;
  }

  @Override
  public @NotNull UnnamedConfigurable createConfigurable(@NotNull HaxeJsProfilerConfigurationState state) {
    return new HaxeJsProfilerConfigurable(state);
  }

  @Override
  public @Nullable String getHelpTopic() {
    return null;
  }

  @Override
  public @NotNull ProfilerStarter createStarter(@NotNull HaxeJsProfilerConfigurationState state) {
    return new ProfilerStarter() {
      /** Drives the run widget's profiler BUTTON: enabled only while the SELECTED configuration is js-profilable. */
      @Override
      public boolean isApplicable(@NotNull Project project) {
        RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
        return selected != null && canRun(selected.getConfiguration());
      }

      /** The launch-time gate, per configuration; the lane check keeps this entry off other targets' runs. */
      @Override
      public boolean canRun(@NotNull RunProfile profile) {
        return profile instanceof HaxeProfilableRunConfiguration configuration
               && configuration.profilingLane() == Lane.JS
               && configuration.isProfilingReady();
      }
    };
  }

  @Override
  public @NotNull ProfilerAttacher createAttacher(@NotNull HaxeJsProfilerConfigurationState state) {
    // profiling needs a browser the IDE launched with its DevTools port; attach-to-running is not a thing
    return new ProfilerAttacher() {
      @Override
      public @NotNull Promise<ProfilerProcess<AttachableTargetProcess>> attachTo(@NotNull AttachableTargetProcess process,
                                                                                 @NotNull Project project) {
        return Promises.rejectedPromise(HaxeProfilerBundle.message("haxe.profiler.attach.unsupported"));
      }
    };
  }
}
