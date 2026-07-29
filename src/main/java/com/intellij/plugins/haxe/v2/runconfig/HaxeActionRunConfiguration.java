package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
import com.intellij.util.PathUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Runs one action of a build file (compile / test / build / a custom action).
 * Stores a REFERENCE (build file path + action name), never a resolved command:
 * the command line re-resolves at launch, so target selection, environment SDK
 * and compilation server settings always apply as currently configured.
 */
public class HaxeActionRunConfiguration extends LocatableConfigurationBase<RunProfileState> {

  private static final String BUILD_FILE = "buildFile";
  private static final String ACTION_NAME = "actionName";
  private static final String EXTRA_ARGUMENTS = "extraArguments";

  private String buildFilePath = "";
  private String actionName = "";
  private String extraArguments = "";

  public HaxeActionRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, @Nullable String name) {
    super(project, factory, name);
  }

  public String getBuildFilePath() {
    return buildFilePath;
  }

  public void setBuildFilePath(@Nullable String path) {
    buildFilePath = StringUtil.notNullize(path);
  }

  public String getActionName() {
    return actionName;
  }

  public void setActionName(@Nullable String name) {
    actionName = StringUtil.notNullize(name);
  }

  public String getExtraArguments() {
    return extraArguments;
  }

  public void setExtraArguments(@Nullable String arguments) {
    extraArguments = StringUtil.notNullize(arguments);
  }

  @Override
  public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new HaxeActionRunConfigurationEditor(getProject());
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    if (StringUtil.isEmptyOrSpaces(buildFilePath)) {
      throw new RuntimeConfigurationError(HaxeBundle.message("haxe.action.config.no.build.file"));
    }
    if (StringUtil.isEmptyOrSpaces(actionName)) {
      throw new RuntimeConfigurationError(HaxeBundle.message("haxe.action.config.no.action"));
    }
    if (HaxeCompileCommands.resolveAction(getProject(), buildFilePath, actionName, extraArguments) == null) {
      throw new RuntimeConfigurationError(
        HaxeBundle.message("haxe.action.config.unresolvable", actionName, PathUtil.getFileName(buildFilePath)));
    }
  }

  @Override
  public @Nullable String suggestedName() {
    if (StringUtil.isEmptyOrSpaces(buildFilePath)) return null;
    return PathUtil.getFileName(buildFilePath) + " " + actionName;
  }

  @Override
  public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
    return new CommandLineState(environment) {
      @Override
      protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        HaxeCompileCommands.Resolved resolved = ReadAction.compute(
          () -> HaxeCompileCommands.resolveAction(getProject(), buildFilePath, actionName, extraArguments));
        if (resolved == null) {
          throw new ExecutionException(
            HaxeBundle.message("haxe.action.config.unresolvable", actionName, PathUtil.getFileName(buildFilePath)));
        }
        List<String> command = HaxeCompileCommands.connectIfEnabled(
          getProject(), resolved.containerId(), resolved.connectEligible(), resolved.command());
        GeneralCommandLine commandLine = new GeneralCommandLine(command)
          .withWorkDirectory(resolved.workDirectory());
        KillableColoredProcessHandler processHandler = new KillableColoredProcessHandler(commandLine);
        ProcessTerminatedListener.attach(processHandler);
        return processHandler;
      }
    };
  }

  @Override
  public void readExternal(@NotNull Element element) {
    super.readExternal(element);
    buildFilePath = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, BUILD_FILE));
    actionName = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, ACTION_NAME));
    extraArguments = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, EXTRA_ARGUMENTS));
  }

  @Override
  public void writeExternal(@NotNull Element element) {
    super.writeExternal(element);
    JDOMExternalizerUtil.writeField(element, BUILD_FILE, buildFilePath);
    JDOMExternalizerUtil.writeField(element, ACTION_NAME, actionName);
    JDOMExternalizerUtil.writeField(element, EXTRA_ARGUMENTS, extraArguments);
  }
}
