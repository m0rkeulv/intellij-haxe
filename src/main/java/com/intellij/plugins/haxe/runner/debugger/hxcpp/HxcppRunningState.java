package com.intellij.plugins.haxe.runner.debugger.hxcpp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.util.execution.ParametersListUtil;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plain (non-debug) execution of a compiled HXCPP executable. Default
 * working directory is the executable's directory so relative resource
 * loading behaves like a manual launch.
 */
public class HxcppRunningState extends CommandLineState {
  private final Module module;
  private final Path executable;
  private final @Nullable Path workingDirectory;
  private final String programArguments;

  public HxcppRunningState(ExecutionEnvironment env, Module module,
                           Path executable, @Nullable Path workingDirectory, String programArguments) {
    super(env);
    this.module = module;
    this.executable = executable;
    this.workingDirectory = workingDirectory;
    this.programArguments = programArguments;
  }

  @NotNull
  @Override
  protected ProcessHandler startProcess() throws ExecutionException {
    setConsoleBuilder(TextConsoleBuilderFactory.getInstance().createBuilder(module.getProject()));
    GeneralCommandLine commandLine = createCommandLine(executable, workingDirectory, programArguments);
    return new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
  }

  public static GeneralCommandLine createCommandLine(Path executable, @Nullable Path workingDirectory, String programArguments) {
    Path workDir = workingDirectory != null ? workingDirectory : executable.getParent();
    GeneralCommandLine commandLine = new GeneralCommandLine()
      .withExePath(executable.toString())
      .withWorkDirectory(workDir != null ? workDir.toString() : null);
    if (!programArguments.isBlank()) {
      commandLine.addParameters(ParametersListUtil.parse(programArguments));
    }
    return commandLine;
  }
}
