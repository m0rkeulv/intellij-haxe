package com.intellij.plugins.haxe.runner.debugger.interp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationModule;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.util.execution.ParametersListUtil;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Haxe interpreter run/debug configuration (experimental): a module (for
 * source lookup) plus the compiler arguments of the compilation to run on the
 * eval VM — an hxml file ({@code build.hxml}) or plain arguments
 * ({@code -cp src -main Main}).
 *
 * Two modes, per the eval design (docs in debuggers/eval-debugger):
 * with "run as interpreter" set (the default), {@code --interp} is appended
 * and the program's OWN code is debugged; without it the arguments are a
 * regular build, and what is debugged are the MACROS that run during that
 * compilation. Debugging needs nothing compiled in — the debug server is
 * part of haxe itself (4.0+).
 */
public class InterpRunConfiguration extends ModuleBasedConfiguration<RunConfigurationModule, Element> {
  private static final String COMPILER_ARGUMENTS = "compilerArguments";
  private static final String WORKING_DIRECTORY = "workingDirectory";
  private static final String RUN_AS_INTERPRETER = "runAsInterpreter";

  @Getter private String compilerArguments = "";
  @Getter private String workingDirectory = "";
  @Getter private boolean runAsInterpreter = true;

  public InterpRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(name, new RunConfigurationModule(project), factory);
  }

  public void setCompilerArguments(@Nullable String arguments) {
    compilerArguments = arguments == null ? "" : arguments;
  }

  public void setWorkingDirectory(@Nullable String directory) {
    workingDirectory = directory == null ? "" : directory;
  }

  public void setRunAsInterpreter(boolean interpret) {
    runAsInterpreter = interpret;
  }

  // --- ModuleBasedConfiguration ---

  @Override
  public Collection<Module> getValidModules() {
    return Arrays.asList(ModuleManager.getInstance(getProject()).getModules());
  }

  @Override
  public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new InterpRunConfigurationEditor(getProject());
  }

  @Override
  public void onNewConfigurationCreated() {
    super.onNewConfigurationCreated();
    if (getConfigurationModule().getModule() == null) {
      Module[] modules = ModuleManager.getInstance(getProject()).getModules();
      if (modules.length > 0) {
        setModule(modules[0]);
      }
    }
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    if (getConfigurationModule().getModule() == null) {
      throw new RuntimeConfigurationError(HaxeBundle.message("interp.runner.no.module"));
    }
    if (compilerArguments.isBlank()) {
      throw new RuntimeConfigurationError(HaxeBundle.message("interp.runner.no.arguments"));
    }
  }

  @Override
  public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    Module module = requireModule();
    return new InterpRunningState(env, module, createCommandLine(List.of()));
  }

  // --- resolution ---

  Module requireModule() throws ExecutionException {
    Module module = getConfigurationModule().getModule();
    if (module == null) {
      throw new ExecutionException(HaxeBundle.message("no.module.for.run.configuration", getName()));
    }
    return module;
  }

  /**
   * The haxe invocation for this configuration, with {@code extraArguments}
   * (e.g. the debugger define) inserted BEFORE the trailing {@code --interp}
   * so they always belong to the compilation itself.
   */
  GeneralCommandLine createCommandLine(List<String> extraArguments) throws ExecutionException {
    if (compilerArguments.isBlank()) {
      throw new ExecutionException(HaxeBundle.message("interp.runner.no.arguments"));
    }
    List<String> arguments = ParametersListUtil.parse(compilerArguments);
    GeneralCommandLine commandLine = new GeneralCommandLine()
      .withExePath("haxe")
      .withWorkDirectory(resolveWorkingDirectory());
    commandLine.addParameters(arguments);
    commandLine.addParameters(extraArguments);
    if (runAsInterpreter && !arguments.contains("--interp")) {
      commandLine.addParameter("--interp");
    }
    return commandLine;
  }

  /** The working directory: the explicit setting, else the project base. */
  private @Nullable String resolveWorkingDirectory() {
    if (!workingDirectory.isBlank()) {
      try {
        return Path.of(workingDirectory).toString();
      } catch (InvalidPathException e) {
        return null;
      }
    }
    return getProject().getBasePath();
  }

  // --- persistence ---

  @Override
  public void readExternal(@NotNull Element element) throws InvalidDataException {
    super.readExternal(element);
    readModule(element);
    compilerArguments = orEmpty(JDOMExternalizerUtil.readField(element, COMPILER_ARGUMENTS));
    workingDirectory = orEmpty(JDOMExternalizerUtil.readField(element, WORKING_DIRECTORY));
    runAsInterpreter = !"false".equals(JDOMExternalizerUtil.readField(element, RUN_AS_INTERPRETER));
  }

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    super.writeExternal(element);
    writeModule(element);
    JDOMExternalizerUtil.writeField(element, COMPILER_ARGUMENTS, compilerArguments);
    JDOMExternalizerUtil.writeField(element, WORKING_DIRECTORY, workingDirectory);
    JDOMExternalizerUtil.writeField(element, RUN_AS_INTERPRETER, Boolean.toString(runAsInterpreter));
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
