package com.intellij.plugins.haxe.runner.debugger.flash;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapCommandLineRunningState;
import com.intellij.plugins.haxe.runner.debugger.dap.ide.DapRunConfigurationBase;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolPathResolver;
import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An AIR run/debug configuration: the application descriptor (application.xml)
 * plus the Flex/AIR SDK whose {@code adl} launches it. Plain Run spawns adl
 * directly — no Flash plugin needed; debugging attaches the Flex debugger
 * (the Flash/Flex plugin must be installed) while adl launches the app.
 * A lime/openfl {@code air} build exports the descriptor at the export root
 * with the content (swf) in {@code bin/} beside it.
 */
public class AirRunConfiguration extends DapRunConfigurationBase {
  private static final String DESCRIPTOR = "descriptorFile";
  private static final String CONTENT_ROOT = "contentRoot";
  private static final String FLEX_SDK = "flexSdk";
  private static final String ADL_OPTIONS = "adlOptions";
  private static final String PROGRAM_PARAMETERS = "programParameters";

  private String descriptorPath = "";
  private String contentRootPath = "";
  private String flexSdkName = "";
  private String adlOptions = "";
  private String programParameters = "";

  public AirRunConfiguration(String name, Project project, ConfigurationFactory factory) {
    super(name, project, factory);
  }

  // --- settings ---

  public String getDescriptorPath() {
    return descriptorPath;
  }

  public void setDescriptorPath(@Nullable String path) {
    descriptorPath = orEmpty(path);
  }

  public String getContentRootPath() {
    return contentRootPath;
  }

  public void setContentRootPath(@Nullable String path) {
    contentRootPath = orEmpty(path);
  }

  public String getFlexSdkName() {
    return flexSdkName;
  }

  public void setFlexSdkName(@Nullable String name) {
    flexSdkName = orEmpty(name);
  }

  public String getAdlOptions() {
    return adlOptions;
  }

  public void setAdlOptions(@Nullable String options) {
    adlOptions = orEmpty(options);
  }

  public String getProgramParameters() {
    return programParameters;
  }

  public void setProgramParameters(@Nullable String parameters) {
    programParameters = orEmpty(parameters);
  }

  /** The Flex/AIR SDK supplying adl (and the debugger): this configuration's own selection, else the Build Tools/Haxe SDK chain. */
  @NotNull
  public String effectiveFlexSdkName() {
    return HaxeToolPathResolver.flexSdkNameOrEmpty(getProject(), flexSdkName);
  }

  @Override
  public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new AirRunConfigurationEditor(getProject());
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    if (getConfigurationModule().getModule() == null) {
      throw new RuntimeConfigurationError(HaxeDebuggerBundle.message("air.runner.no.module"));
    }
    if (descriptorPath.isBlank()) {
      throw new RuntimeConfigurationError(HaxeDebuggerBundle.message("air.runner.no.descriptor"));
    }
    Path descriptor = resolveAgainstProject(descriptorPath);
    if (!Files.isRegularFile(descriptor)) {
      throw new RuntimeConfigurationWarning(HaxeDebuggerBundle.message("air.runner.descriptor.missing", descriptorPath));
    }
    if (effectiveFlexSdkName().isBlank()) {
      throw new RuntimeConfigurationWarning(HaxeDebuggerBundle.message("air.runner.no.flex.sdk"));
    }
  }

  // Plain Run: adl launches the app. Resolution stays inside the supplier -
  // getState runs before before-launch tasks, so the descriptor a build step
  // produces may not exist yet.
  @Override
  public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    requireModule();
    return new DapCommandLineRunningState(env, getProject(), () -> createAdlCommandLine(false));
  }

  /**
   * {@code adl [options] [-nodebug] <descriptor> <content-root> [-- args]},
   * with adl from the effective Flex/AIR SDK's bin directory. The SDK entry is
   * read from the plain SDK table, so plain Run works without the Flash plugin.
   * Plain Run passes {@code -nodebug}: adl's default debug-launch mode swallows
   * trace output; a debug launch must NOT pass it, or the swf never connects
   * to fdb. With multiple instances allowed, each launch runs a descriptor
   * COPY carrying a unique app id (see {@link #uniqueIdDescriptor}).
   */
  @NotNull
  public GeneralCommandLine createAdlCommandLine(boolean debugLaunch) throws ExecutionException {
    Path descriptor = resolveDescriptor();
    Path contentRoot = resolveContentRoot(descriptor);
    if (isAllowRunningInParallel()) {
      descriptor = uniqueIdDescriptor(descriptor);
    }

    GeneralCommandLine commandLine = new GeneralCommandLine()
      .withExePath(resolveAdl().toString())
      .withWorkDirectory(contentRoot.toString());
    commandLine.addParameters(ParametersListUtil.parse(adlOptions));
    if (!debugLaunch) {
      commandLine.addParameter("-nodebug");
    }
    commandLine.addParameter(descriptor.toString());
    commandLine.addParameter(contentRoot.toString());

    List<String> programArguments = ParametersListUtil.parse(programParameters);
    if (!programArguments.isEmpty()) {
      commandLine.addParameter("--");
      commandLine.addParameters(programArguments);
    }
    return commandLine;
  }

  /**
   * AIR is single-instance per application id (a second same-id launch just
   * forwards to the first and exits; {@code -pubid} is rejected for post-1.5.3
   * namespaces), so a parallel launch runs a COPY of the descriptor whose id
   * carries a unique suffix. The content root still points at the original
   * content; the suffixed id also gives the instance its own application
   * storage.
   */
  @NotNull
  private static Path uniqueIdDescriptor(@NotNull Path descriptor) throws ExecutionException {
    try {
      String content = Files.readString(descriptor);
      String suffix = ".ij" + UUID.randomUUID().toString().substring(0, 8);
      // the first <id>...</id> element is the application id; the suffix lands inside it
      String modified = content.replaceFirst("(<id>[^<]*)</id>", "$1" + suffix + "</id>");
      if (modified.equals(content)) {
        throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.descriptor.no.id", descriptor.toString()));
      }
      Path copy = Files.createTempDirectory("haxe-air-run").resolve(descriptor.getFileName());
      Files.writeString(copy, modified);
      return copy;
    }
    catch (IOException e) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.descriptor.copy.failed", e.getMessage()));
    }
  }

  // --- resolution ---

  @NotNull
  private Path resolveDescriptor() throws ExecutionException {
    if (descriptorPath.isBlank()) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.no.descriptor"));
    }
    Path descriptor = resolveAgainstProject(descriptorPath);
    if (!Files.isRegularFile(descriptor)) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.descriptor.missing", descriptor.toString()));
    }
    return descriptor;
  }

  /** The directory the descriptor's {@code <content>} resolves against; the descriptor's own directory when unset. */
  @NotNull
  private Path resolveContentRoot(@NotNull Path descriptor) {
    if (contentRootPath.isBlank()) {
      Path parent = descriptor.getParent();
      return parent != null ? parent : descriptor;
    }
    return resolveAgainstProject(contentRootPath);
  }

  @NotNull
  private Path resolveAdl() throws ExecutionException {
    String sdkName = effectiveFlexSdkName();
    if (sdkName.isBlank()) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.no.flex.sdk"));
    }
    Sdk sdk = ProjectJdkTable.getInstance().findJdk(sdkName);
    if (sdk == null || sdk.getHomePath() == null) {
      throw new ExecutionException(HaxeBundle.message("flex.sdk.not.found", sdkName));
    }
    Path adl = Path.of(sdk.getHomePath(), "bin", HaxeSdkUtilBase.getExecutableName("adl"));
    if (!Files.isRegularFile(adl)) {
      throw new ExecutionException(HaxeDebuggerBundle.message("air.runner.adl.missing", sdkName, adl.toString()));
    }
    return adl;
  }

  // --- persistence ---

  @Override
  public void readExternal(@NotNull Element element) throws InvalidDataException {
    super.readExternal(element);
    readModule(element);
    descriptorPath = orEmpty(JDOMExternalizerUtil.readField(element, DESCRIPTOR));
    contentRootPath = orEmpty(JDOMExternalizerUtil.readField(element, CONTENT_ROOT));
    flexSdkName = orEmpty(JDOMExternalizerUtil.readField(element, FLEX_SDK));
    adlOptions = orEmpty(JDOMExternalizerUtil.readField(element, ADL_OPTIONS));
    programParameters = orEmpty(JDOMExternalizerUtil.readField(element, PROGRAM_PARAMETERS));
  }

  @Override
  public void writeExternal(@NotNull Element element) throws WriteExternalException {
    super.writeExternal(element); // also serializes the module
    JDOMExternalizerUtil.writeField(element, DESCRIPTOR, descriptorPath);
    JDOMExternalizerUtil.writeField(element, CONTENT_ROOT, contentRootPath);
    JDOMExternalizerUtil.writeField(element, FLEX_SDK, flexSdkName);
    JDOMExternalizerUtil.writeField(element, ADL_OPTIONS, adlOptions);
    JDOMExternalizerUtil.writeField(element, PROGRAM_PARAMETERS, programParameters);
  }
}
