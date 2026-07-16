package com.intellij.plugins.haxe.runner.debugger.hxcpp.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationModule;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.runner.debugger.hxcpp.HxcppRunningState;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import lombok.Getter;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An HXCPP (IntelliJ debug server) run/debug configuration: a module for
 * source lookup plus the compiled native executable. For debugging, the
 * executable must have been compiled with {@code -debug} and {@code -lib
 * intellij-hxcpp-debug-server}. No host/port settings: the IDE listens on an
 * ephemeral loopback port per session and hands it to the debuggee through
 * env vars, so nothing is baked into the build and concurrent sessions never
 * collide. A build with the library runs normally outside the debugger (the
 * server makes one quick connect attempt and stays out of the way).
 */
public class HxcppIntellijRunConfiguration extends ModuleBasedConfiguration<RunConfigurationModule, Element> {
  private static final String EXECUTABLE = "executable";
  private static final String WORKING_DIRECTORY = "workingDirectory";
  private static final String PROGRAM_ARGUMENTS = "programArguments";

  @Getter private String executablePath = "";
  @Getter private String workingDirectory = "";
  @Getter private String programArguments = "";

  public HxcppIntellijRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(name, new RunConfigurationModule(project), factory);
  }

  // --- settings (setters normalize their input, so they stay hand-written) ---

  public void setExecutablePath(@Nullable String path) {
    executablePath = path == null ? "" : path;
  }

  public void setWorkingDirectory(@Nullable String directory) {
    workingDirectory = directory == null ? "" : directory;
  }

  public void setProgramArguments(@Nullable String arguments) {
    programArguments = arguments == null ? "" : arguments;
  }

  // --- ModuleBasedConfiguration ---

  @Override
  public Collection<Module> getValidModules() {
    return Arrays.asList(ModuleManager.getInstance(getProject()).getModules());
  }

  @Override
  public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new HxcppIntellijRunConfigurationEditor(getProject());
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
      throw new RuntimeConfigurationError(HaxeBundle.message("hxcpp.runner.no.module"));
    }
    if (executablePath.isBlank()) {
      throw new RuntimeConfigurationError(HaxeBundle.message("hxcpp.runner.no.executable"));
    }
    Path executable = resolveExecutableOrNull();
    if (executable == null || !Files.isRegularFile(executable)) {
      throw new RuntimeConfigurationWarning(
        HaxeBundle.message("hxcpp.runner.executable.missing", executablePath));
    }
  }

  @Override
  public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    Module module = requireModule();
    return new HxcppRunningState(env, module, resolveExecutable(), resolveWorkingDirectory(), programArguments);
  }

  // --- resolution ---

  public Module requireModule() throws ExecutionException {
    Module module = getConfigurationModule().getModule();
    if (module == null) {
      throw new ExecutionException(HaxeBundle.message("no.module.for.run.configuration", getName()));
    }
    return module;
  }

  public Path resolveExecutable() throws ExecutionException {
    Path executable = resolveExecutableOrNull();
    if (executable == null || !Files.isRegularFile(executable)) {
      throw new ExecutionException(
        HaxeBundle.message("hxcpp.runner.executable.missing", executablePath.isBlank() ? "<not set>" : executablePath));
    }
    return executable;
  }

  private @Nullable Path resolveExecutableOrNull() {
    if (executablePath.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(executablePath);
      if (path.isAbsolute()) {
        return path;
      }
      String basePath = getProject().getBasePath();
      return basePath != null ? Path.of(basePath).resolve(path) : path;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /** The working directory: the explicit setting, else the executable's directory. */
  public @Nullable Path resolveWorkingDirectory() {
    if (!workingDirectory.isBlank()) {
      try {
        return Path.of(workingDirectory);
      } catch (InvalidPathException e) {
        return null;
      }
    }
    Path executable = resolveExecutableOrNull();
    return executable != null ? executable.getParent() : null;
  }

  // --- persistence ---

  @Override
  public void readExternal(@NotNull Element element) throws InvalidDataException {
    super.readExternal(element);
    readModule(element);
    executablePath = orEmpty(JDOMExternalizerUtil.readField(element, EXECUTABLE));
    workingDirectory = orEmpty(JDOMExternalizerUtil.readField(element, WORKING_DIRECTORY));
    programArguments = orEmpty(JDOMExternalizerUtil.readField(element, PROGRAM_ARGUMENTS));
  }

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    super.writeExternal(element);
    writeModule(element);
    JDOMExternalizerUtil.writeField(element, EXECUTABLE, executablePath);
    JDOMExternalizerUtil.writeField(element, WORKING_DIRECTORY, workingDirectory);
    JDOMExternalizerUtil.writeField(element, PROGRAM_ARGUMENTS, programArguments);
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
