package com.intellij.plugins.haxe.runner.nodejs;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.HaxeRuntimeRunConfiguration;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Node.js application run configuration: the node runtime with the
 * compiled {@code .js} script as its program argument. Run only.
 */
// TODO: Debug through the bundled js-debug adapter's node launch, and a profiler lane over the inspector protocol
public class NodeRunConfiguration extends HaxeRuntimeRunConfiguration {

  public NodeRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(name, project, factory);
  }

  @Override
  protected @NotNull String runtimeName() {
    return "node";
  }

  @Override
  protected @Nullable String locateRuntime() {
    return HaxeToolPathResolver.resolveNodeExecutable(getProject(), null);
  }

  @Override
  protected @NotNull String runtimeMissingMessage() {
    return HaxeDebuggerBundle.message("node.runner.runtime.missing");
  }

  @Override
  protected @NotNull String executableHint() {
    return HaxeDebuggerBundle.message("node.runner.editor.executable.hint");
  }
}
