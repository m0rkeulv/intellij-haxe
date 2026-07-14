package com.intellij.plugins.haxe.hxcpp;

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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An HXCPP run/debug configuration (experimental): a module (for source
 * lookup) plus the compiled native executable. For debugging, the executable
 * must have been compiled with {@code -debug} and {@code -lib
 * hxcpp-debug-server}; its embedded debug server connects out to
 * host:port, which are compile-time defines in the executable
 * (HXCPP_DEBUG_HOST/HXCPP_DEBUG_PORT) — the fields here are prefilled with
 * the protocol defaults and only need changing when the build overrides them.
 */
public class HxcppRunConfiguration extends ModuleBasedConfiguration<RunConfigurationModule, Element> {
  public static final String DEFAULT_DEBUG_HOST = "127.0.0.1";
  public static final int DEFAULT_DEBUG_PORT = 6972;

  private static final String EXECUTABLE = "executable";
  private static final String WORKING_DIRECTORY = "workingDirectory";
  private static final String PROGRAM_ARGUMENTS = "programArguments";
  private static final String DEBUG_HOST = "debugHost";
  private static final String DEBUG_PORT = "debugPort";

  private String executablePath = "";
  private String workingDirectory = "";
  private String programArguments = "";
  private String debugHost = DEFAULT_DEBUG_HOST;
  private String debugPort = Integer.toString(DEFAULT_DEBUG_PORT);

  public HxcppRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(name, new RunConfigurationModule(project), factory);
  }

  // --- settings ---

  public String getExecutablePath() {
    return executablePath;
  }

  public void setExecutablePath(@Nullable String path) {
    executablePath = path == null ? "" : path;
  }

  public String getWorkingDirectory() {
    return workingDirectory;
  }

  public void setWorkingDirectory(@Nullable String directory) {
    workingDirectory = directory == null ? "" : directory;
  }

  public String getProgramArguments() {
    return programArguments;
  }

  public void setProgramArguments(@Nullable String arguments) {
    programArguments = arguments == null ? "" : arguments;
  }

  public String getDebugHost() {
    return debugHost;
  }

  public void setDebugHost(@Nullable String host) {
    debugHost = host == null || host.isBlank() ? DEFAULT_DEBUG_HOST : host.trim();
  }

  public String getDebugPort() {
    return debugPort;
  }

  public void setDebugPort(@Nullable String port) {
    debugPort = port == null || port.isBlank() ? Integer.toString(DEFAULT_DEBUG_PORT) : port.trim();
  }

  int resolveDebugPort() throws ExecutionException {
    int port = parsedDebugPort();
    if (port < 0) {
      throw new ExecutionException(HaxeBundle.message("hxcpp.runner.bad.port"));
    }
    return port;
  }

  private int parsedDebugPort() {
    try {
      int port = Integer.parseInt(debugPort);
      return port >= 1 && port <= 65535 ? port : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // --- ModuleBasedConfiguration ---

  @Override
  public Collection<Module> getValidModules() {
    return Arrays.asList(ModuleManager.getInstance(getProject()).getModules());
  }

  @Override
  public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new HxcppRunConfigurationEditor(getProject());
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
    if (parsedDebugPort() < 0) {
      throw new RuntimeConfigurationError(HaxeBundle.message("hxcpp.runner.bad.port"));
    }
  }

  @Override
  public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    Module module = requireModule();
    return new HxcppRunningState(env, module, resolveExecutable(), resolveWorkingDirectory(), programArguments);
  }

  // --- resolution ---

  Module requireModule() throws ExecutionException {
    Module module = getConfigurationModule().getModule();
    if (module == null) {
      throw new ExecutionException(HaxeBundle.message("no.module.for.run.configuration", getName()));
    }
    return module;
  }

  Path resolveExecutable() throws ExecutionException {
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
  @Nullable Path resolveWorkingDirectory() {
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
    setDebugHost(JDOMExternalizerUtil.readField(element, DEBUG_HOST));
    setDebugPort(JDOMExternalizerUtil.readField(element, DEBUG_PORT));
  }

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    super.writeExternal(element);
    writeModule(element);
    JDOMExternalizerUtil.writeField(element, EXECUTABLE, executablePath);
    JDOMExternalizerUtil.writeField(element, WORKING_DIRECTORY, workingDirectory);
    JDOMExternalizerUtil.writeField(element, PROGRAM_ARGUMENTS, programArguments);
    JDOMExternalizerUtil.writeField(element, DEBUG_HOST, debugHost);
    JDOMExternalizerUtil.writeField(element, DEBUG_PORT, debugPort);
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
