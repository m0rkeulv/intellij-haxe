package com.intellij.plugins.haxe.profiler.bridge.flash;

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
 * The "Flash Profiler" entry of the IU Run-with-Profiler executor: an AIR
 * launch in the DEBUGGER runtime (adl without -nodebug) whose swf carries
 * {@code -D advanced-telemetry}. The runtime streams its own telemetry —
 * frames, render spans, sampler stacks (~1 ms, its fixed rate), memory and
 * GC — to the IDE, which transcodes it while the program runs.
 */
public class HaxeFlashProfilerConfigurationType implements ProfilerConfigurationType<HaxeFlashProfilerConfigurationState> {

  public static final String ID = "HaxeFlashProfilerConfiguration";
  private static final String NAME_ATTRIBUTE = "name";

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return HaxeProfilerBundle.message("haxe.profiler.flash.configuration.name");
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
  public @NotNull HaxeFlashProfilerConfigurationState getTemplateState() {
    return new HaxeFlashProfilerConfigurationState(getDisplayName());
  }

  @Override
  public @NotNull HaxeFlashProfilerConfigurationState copyState(@NotNull HaxeFlashProfilerConfigurationState state) {
    return new HaxeFlashProfilerConfigurationState(state.getDisplayName());
  }

  @Override
  public @NotNull HaxeFlashProfilerConfigurationState readState(@NotNull Element element) {
    HaxeFlashProfilerConfigurationState state = getTemplateState();
    String name = element.getAttributeValue(NAME_ATTRIBUTE);
    if (name != null) state.setDisplayName(name);
    return state;
  }

  @Override
  public @NotNull Element writeState(@NotNull HaxeFlashProfilerConfigurationState state) {
    Element element = new Element("haxeFlashProfiler");
    element.setAttribute(NAME_ATTRIBUTE, state.getDisplayName());
    return element;
  }

  @Override
  public @NotNull UnnamedConfigurable createConfigurable(@NotNull HaxeFlashProfilerConfigurationState state) {
    return new HaxeFlashProfilerConfigurable();
  }

  @Override
  public @Nullable String getHelpTopic() {
    return null;
  }

  @Override
  public @NotNull ProfilerStarter createStarter(@NotNull HaxeFlashProfilerConfigurationState state) {
    return new ProfilerStarter() {
      /** Drives the run widget's profiler BUTTON: enabled only while the SELECTED configuration is flash-profilable. */
      @Override
      public boolean isApplicable(@NotNull Project project) {
        RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
        return selected != null && canRun(selected.getConfiguration());
      }

      /** The launch-time gate, per configuration; the lane check keeps this entry off other targets' runs. */
      @Override
      public boolean canRun(@NotNull RunProfile profile) {
        return profile instanceof HaxeProfilableRunConfiguration configuration
               && configuration.profilingLane() == Lane.FLASH
               && configuration.isProfilingReady();
      }
    };
  }

  @Override
  public @NotNull ProfilerAttacher createAttacher(@NotNull HaxeFlashProfilerConfigurationState state) {
    // telemetry starts with the process (the swf carries the opt-in tag); attach-to-running is not a thing
    return new ProfilerAttacher() {
      @Override
      public @NotNull Promise<ProfilerProcess<AttachableTargetProcess>> attachTo(@NotNull AttachableTargetProcess process,
                                                                                 @NotNull Project project) {
        return Promises.rejectedPromise(HaxeProfilerBundle.message("haxe.profiler.attach.unsupported"));
      }
    };
  }
}
