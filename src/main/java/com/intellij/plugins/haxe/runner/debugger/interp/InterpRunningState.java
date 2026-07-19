package com.intellij.plugins.haxe.runner.debugger.interp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;

/**
 * Plain (non-debug) execution of a Haxe interpreter configuration: runs the
 * prepared {@code haxe ...} command line and shows its output in the console.
 */
public class InterpRunningState extends CommandLineState {
  private final Module module;
  private final GeneralCommandLine commandLine;

  public InterpRunningState(ExecutionEnvironment env, Module module, GeneralCommandLine commandLine) {
    super(env);
    this.module = module;
    this.commandLine = commandLine;
  }

  @NotNull
  @Override
  protected ProcessHandler startProcess() throws ExecutionException {
    setConsoleBuilder(TextConsoleBuilderFactory.getInstance().createBuilder(module.getProject()));
    return new ColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
  }
}
