package com.intellij.plugins.haxe.profiler.bridge.tracy;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration.Lane;
import com.intellij.plugins.haxe.profiler.HaxeProfilableRunConfiguration;
import com.intellij.plugins.haxe.profiler.tracy.wire.TracyProtocolVersion;
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
 * The "hxcpp Tracy" entry of the IU Run-with-Profiler executor: builds the
 * program with hxcpp's Tracy client compiled in ({@code -D HXCPP_TRACY} —
 * exact instrumented zones for every haxe function), captures the stream
 * with the pure-Java receiver and saves it as a session file. Needs the
 * hxcpp version that bundles Tracy (haxe 5 era).
 */
public class HaxeHxcppTracyProfilerConfigurationType implements ProfilerConfigurationType<HaxeHxcppTracyProfilerConfigurationState> {

  public static final String ID = "HaxeHxcppTracyProfilerConfiguration";
  private static final String NAME_ATTRIBUTE = "name";
  private static final String COMPRESSION_ATTRIBUTE = "compressionLevel";
  private static final String CAPTURE_MEMORY_ATTRIBUTE = "captureMemory";
  private static final String COLLECT_PROCESS_CPU_ATTRIBUTE = "collectProcessCpu";
  /** The pinned protocol's wire number; absent or unparsable = detect. */
  private static final String PROTOCOL_ATTRIBUTE = "protocolVersion";

  @Override
  public @NotNull String getId() {
    return ID;
  }

  @Override
  public @NotNull String getDisplayName() {
    return HaxeProfilerBundle.message("haxe.profiler.hxcpp.tracy.configuration.name");
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
  public @NotNull HaxeHxcppTracyProfilerConfigurationState getTemplateState() {
    return new HaxeHxcppTracyProfilerConfigurationState(getDisplayName());
  }

  @Override
  public @NotNull HaxeHxcppTracyProfilerConfigurationState copyState(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    HaxeHxcppTracyProfilerConfigurationState copy = new HaxeHxcppTracyProfilerConfigurationState(state.getDisplayName());
    copy.setCompressionLevel(state.getCompressionLevel());
    copy.setCaptureMemory(state.isCaptureMemory());
    copy.setCollectProcessCpu(state.isCollectProcessCpu());
    copy.setPinnedProtocol(state.getPinnedProtocol());
    return copy;
  }

  @Override
  public @NotNull HaxeHxcppTracyProfilerConfigurationState readState(@NotNull Element element) {
    HaxeHxcppTracyProfilerConfigurationState state = getTemplateState();
    String name = element.getAttributeValue(NAME_ATTRIBUTE);
    if (name != null) state.setDisplayName(name);
    try {
      state.setCompressionLevel(Integer.parseInt(element.getAttributeValue(COMPRESSION_ATTRIBUTE, "")));
    }
    catch (NumberFormatException ignored) {
      // keep the template default
    }
    state.setCaptureMemory(!"false".equals(element.getAttributeValue(CAPTURE_MEMORY_ATTRIBUTE)));
    state.setCollectProcessCpu("true".equals(element.getAttributeValue(COLLECT_PROCESS_CPU_ATTRIBUTE)));
    state.setPinnedProtocol(pinnedProtocol(element.getAttributeValue(PROTOCOL_ATTRIBUTE)));
    return state;
  }

  @Override
  public @NotNull Element writeState(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    Element element = new Element("haxeHxcppTracyProfiler");
    element.setAttribute(NAME_ATTRIBUTE, state.getDisplayName());
    element.setAttribute(COMPRESSION_ATTRIBUTE, String.valueOf(state.getCompressionLevel()));
    element.setAttribute(CAPTURE_MEMORY_ATTRIBUTE, String.valueOf(state.isCaptureMemory()));
    element.setAttribute(COLLECT_PROCESS_CPU_ATTRIBUTE, String.valueOf(state.isCollectProcessCpu()));
    TracyProtocolVersion pinned = state.getPinnedProtocol();
    if (pinned != null) {
      element.setAttribute(PROTOCOL_ATTRIBUTE, String.valueOf(pinned.wire()));
    }
    return element;
  }

  @Nullable
  private static TracyProtocolVersion pinnedProtocol(@Nullable String attribute) {
    if (attribute == null) return null;
    try {
      return TracyProtocolVersion.of(Integer.parseInt(attribute));
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  public @NotNull UnnamedConfigurable createConfigurable(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    return new HaxeHxcppTracyProfilerConfigurable(state);
  }

  @Override
  public @Nullable String getHelpTopic() {
    return null;
  }

  @Override
  public @NotNull ProfilerStarter createStarter(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
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
  public @NotNull ProfilerAttacher createAttacher(@NotNull HaxeHxcppTracyProfilerConfigurationState state) {
    // the client is compiled into the binary and starts with the process; attach-to-running is not a thing
    return new ProfilerAttacher() {
      @Override
      public @NotNull Promise<ProfilerProcess<AttachableTargetProcess>> attachTo(@NotNull AttachableTargetProcess process,
                                                                                 @NotNull Project project) {
        return Promises.rejectedPromise(HaxeProfilerBundle.message("haxe.profiler.attach.unsupported"));
      }
    };
  }
}
