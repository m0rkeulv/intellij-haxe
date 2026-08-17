package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.build.BuildDescriptor;
import com.intellij.build.BuildViewManager;
import com.intellij.build.DefaultBuildDescriptor;
import com.intellij.build.process.BuildProcessHandler;
import com.intellij.build.progress.BuildProgress;
import com.intellij.build.progress.BuildProgressDescriptor;
import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.v2.buildsystem.*;
import com.intellij.plugins.haxe.v2.buildtools.*;
import com.intellij.plugins.haxe.v2.testing.run.HaxeTestRunConfiguration;
import com.intellij.util.PathUtil;
import icons.HaxeIcons;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Before-launch step that runs a Haxe action (a build file's compile/build command).
 * Under the Debug executor the target's debug additions (e.g. {@code -debug}) are
 * appended automatically, so one visible configuration compiles a normal build on
 * Run and a debuggable build on Debug.
 */
public final class HaxeActionBeforeRunTaskProvider extends BeforeRunTaskProvider<HaxeActionBeforeRunTaskProvider.Task> {

  public static final Key<Task> ID = Key.create("HaxeActionBeforeRun");

  public static final class Task extends BeforeRunTask<Task> {
    private static final String BUILD_FILE = "buildFile";
    private static final String ACTION = "action";
    private static final String ARGUMENTS = "arguments";
    private static final String INJECT_DEBUG = "injectDebugArguments";
    private static final String SECTION_SCOPED = "sectionScoped";

    private String buildFilePath = "";
    private String actionName = HxmlProjects.BUILD_ACTION;
    private String extraArguments = "";
    // opt-out for projects whose build files already carry the debug flags/lib
    private boolean injectDebugArguments = true;
    // test compiles set this: a multi-section hxml compiles only its selected
    // --next section, so the extra arguments reach that section (they would
    // otherwise land in the LAST one - haxe's trailing-argument rule)
    private boolean sectionScoped = false;

    public Task() {
      super(ID);
    }

    public String getBuildFilePath() {
      return buildFilePath;
    }

    public String getActionName() {
      return actionName;
    }

    public String getExtraArguments() {
      return extraArguments;
    }

    public boolean isInjectDebugArguments() {
      return injectDebugArguments;
    }

    public void setBuildFilePath(@Nullable String path) {
      buildFilePath = StringUtil.notNullize(path);
    }

    public void setActionName(@Nullable String name) {
      actionName = StringUtil.notNullize(name);
    }

    public void setExtraArguments(@Nullable String arguments) {
      extraArguments = StringUtil.notNullize(arguments);
    }

    public void setInjectDebugArguments(boolean inject) {
      injectDebugArguments = inject;
    }

    public boolean isSectionScoped() {
      return sectionScoped;
    }

    public void setSectionScoped(boolean scoped) {
      sectionScoped = scoped;
    }

    @Override
    public void writeExternal(@NotNull Element element) {
      super.writeExternal(element);
      JDOMExternalizerUtil.writeField(element, BUILD_FILE, buildFilePath);
      JDOMExternalizerUtil.writeField(element, ACTION, actionName);
      JDOMExternalizerUtil.writeField(element, ARGUMENTS, extraArguments);
      JDOMExternalizerUtil.writeField(element, INJECT_DEBUG, String.valueOf(injectDebugArguments));
      JDOMExternalizerUtil.writeField(element, SECTION_SCOPED, String.valueOf(sectionScoped));
    }

    @Override
    public void readExternal(@NotNull Element element) {
      super.readExternal(element);
      buildFilePath = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, BUILD_FILE));
      actionName = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, ACTION),
                                         HxmlProjects.BUILD_ACTION);
      extraArguments = StringUtil.notNullize(JDOMExternalizerUtil.readField(element, ARGUMENTS));
      // absent in configurations saved before the option existed - keep injecting
      injectDebugArguments = !"false".equals(JDOMExternalizerUtil.readField(element, INJECT_DEBUG));
      sectionScoped = "true".equals(JDOMExternalizerUtil.readField(element, SECTION_SCOPED));
    }
  }

  @Override
  public Key<Task> getId() {
    return ID;
  }

  @Override
  public String getName() {
    return HaxeDebuggerBundle.message("haxe.before.run.name");
  }

  @Override
  public Icon getIcon() {
    return HaxeIcons.HAXE_LOGO;
  }

  @Override
  public String getDescription(Task task) {
    if (task.getBuildFilePath().isEmpty()) {
      return HaxeDebuggerBundle.message("haxe.before.run.name");
    }
    return HaxeDebuggerBundle.message("haxe.before.run.description",
                                      task.getActionName(), PathUtil.getFileName(task.getBuildFilePath()));
  }

  @Override
  public boolean isConfigurable() {
    return true;
  }

  @Override
  public @Nullable Task createTask(@NotNull RunConfiguration configuration) {
    return new Task();
  }

  @Override
  public @NotNull Promise<Boolean> configureTask(@NotNull DataContext context,
                                                 @NotNull RunConfiguration configuration,
                                                 @NotNull Task task) {
    HaxeActionBeforeRunDialog dialog = new HaxeActionBeforeRunDialog(configuration.getProject(), task);
    return Promises.resolvedPromise(dialog.showAndGet());
  }

  @Override
  public boolean executeTask(@NotNull DataContext context,
                             @NotNull RunConfiguration configuration,
                             @NotNull ExecutionEnvironment environment,
                             @NotNull Task task) {
    Project project = configuration.getProject();
    boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(environment.getExecutor().getId());

    // safe mode blocks run configurations platform-side; this is the backstop
    // for any launch path that slips through - no dialog off the EDT
    if (!HaxeProjectTrust.isTrusted(project)) {
      HaxeCommandNotifications.notify(project, getName(),
                                      HaxeDebuggerBundle.message("haxe.before.run.untrusted"),
                                      NotificationType.ERROR);
      return false;
    }

    HaxeUnsavedDocuments.saveAll();
    // test compiles derive their arguments at LAUNCH - the task's stored
    // snapshot goes stale when the framework/reporter wiring evolves. A
    // single-run (gutter) compile arrives fully formed: its generated main
    // replaces the build's own, so the action-plus-file resolution and the
    // section scoping below must not touch it.
    boolean singleRun = configuration instanceof HaxeTestRunConfiguration testConfiguration
                        && testConfiguration.hasSingleRun();
    HaxeCompileCommands.Resolved resolved;
    if (singleRun) {
      resolved = ((HaxeTestRunConfiguration)configuration).resolveSingleRunCompile();
    } else {
      String extraArguments = configuration instanceof HaxeTestRunConfiguration testConfiguration
                              ? testConfiguration.currentCompileArguments()
                              : task.getExtraArguments();
      resolved = ReadAction.computeBlocking(
        () -> HaxeCompileCommands.resolveAction(project, task.getBuildFilePath(), task.getActionName(), extraArguments));
    }
    if (resolved == null) {
      notifyFailure(project, HaxeDebuggerBundle.message("haxe.before.run.unresolvable", task.getBuildFilePath()));
      return false;
    }
    HaxeCompileCommands.Resolved resolvedCompile = resolved;

    List<String> base = !singleRun && task.isSectionScoped()
      ? ReadAction.computeBlocking(() -> sectionScopedCommand(project, task.getBuildFilePath(), resolvedCompile.command()))
      : resolved.command();
    List<String> command = new ArrayList<>(base);
    if (debug && task.isInjectDebugArguments()) {
      // a single-run compile is a DIRECT haxe compile whatever the build
      // system, so its additions use the haxe spelling - the tool spellings
      // (lime's --haxelib=) are unknown options to haxe itself
      List<String> additions = singleRun
                               ? singleRunDebugAdditions(project, task.getBuildFilePath())
                               : debugAdditions(project, task.getBuildFilePath());
      if (additions != null) {
        command.addAll(additions);
      }
    }
    command = HaxeCompileCommands.connectIfEnabled(project, resolved.containerId(), resolved.connectEligible(), command);

    // the compile streams into the Build tool window (activated on start) -
    // it runs before the launch, and without visible output a native build's
    // minutes of compilation look like a hang
    String title = HaxeDebuggerBundle.message("haxe.before.run.build.title",
                                              task.getActionName(), PathUtil.getFileName(task.getBuildFilePath()));
    String workDirectory = StringUtil.notNullize(resolved.workDirectory(), StringUtil.notNullize(project.getBasePath()));

    CapturingProcessHandler handler;
    try {
      GeneralCommandLine commandLine = new GeneralCommandLine(command)
        .withWorkDirectory(resolved.workDirectory())
        .withEnvironment(LimeProjects.commandEnvironment(command));
      handler = new CapturingProcessHandler(commandLine);
    }
    catch (ExecutionException e) {
      notifyFailure(project, StringUtil.notNullize(e.getMessage()));
      return false;
    }

    BuildProgress<BuildProgressDescriptor> progress = BuildViewManager.createBuildProgress(project);
    // the root progress takes its id FROM the descriptor - progress.getId()
    // asserts before start(), so the build id must be our own object
    DefaultBuildDescriptor buildDescriptor =
      new DefaultBuildDescriptor(new Object(), title, workDirectory, System.currentTimeMillis());
    buildDescriptor.setActivateToolWindowWhenAdded(true);
    // surfacing the compile as the build's process handler enables the Build
    // view's Stop action - the escape hatch when a compile hangs (e.g. on a
    // wedged compilation server connection)
    buildDescriptor.withProcessHandler(new CompileProcessHandler(handler, title), null);
    progress.start(descriptorFor(title, buildDescriptor));

    // the process handler announces the command line as its first output -
    // printing it manually doubles the line
    handler.addProcessListener(new ProcessListener() {
      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        progress.output(event.getText(),
                        outputType instanceof ProcessOutputType type ? type : ProcessOutputType.STDOUT);
      }
    });
    ProcessOutput output = handler.runProcess();
    if (output.getExitCode() != 0) {
      progress.fail(System.currentTimeMillis(),
                    HaxeDebuggerBundle.message("haxe.before.run.failed", String.valueOf(output.getExitCode())));
      return false;
    }
    progress.finish();
    return true;
  }

  /** Adapts the compiler process to the Build view: its Stop action destroys the underlying process. */
  private static final class CompileProcessHandler extends BuildProcessHandler {
    private final CapturingProcessHandler delegate;
    private final String executionName;

    CompileProcessHandler(@NotNull CapturingProcessHandler delegate, @NotNull String executionName) {
      this.delegate = delegate;
      this.executionName = executionName;
      delegate.addProcessListener(new ProcessListener() {
        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
          notifyProcessTerminated(event.getExitCode());
        }
      });
    }

    @Override
    public String getExecutionName() {
      return executionName;
    }

    @Override
    protected void destroyProcessImpl() {
      delegate.destroyProcess();
    }

    @Override
    protected void detachProcessImpl() {
      delegate.detachProcess();
      notifyProcessDetached();
    }

    @Override
    public boolean detachIsDefault() {
      return false;
    }

    @Override
    public @Nullable OutputStream getProcessInput() {
      return null;
    }
  }

  @NotNull
  private static BuildProgressDescriptor descriptorFor(@NotNull String title,
                                                       @NotNull DefaultBuildDescriptor buildDescriptor) {
    return new BuildProgressDescriptor() {
      @Override
      public @NotNull String getTitle() {
        return title;
      }

      @Override
      public @NotNull BuildDescriptor getBuildDescriptor() {
        return buildDescriptor;
      }
    };
  }

  /** The resolved command scoped to the hxml's selected {@code --next} section; unchanged for non-hxml files. */
  @NotNull
  private static List<String> sectionScopedCommand(@NotNull Project project,
                                                   @NotNull String buildFilePath,
                                                   @NotNull List<String> command) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null || HaxeBuildFileScanner.detectType(project, file) != HaxeBuildFileType.HXML) return command;
    return HxmlProjects.scopeToSelectedSection(project, file, command);
  }

  /**
   * The build's classpath roots from the configuration's Haxe build step, or
   * empty without one — debug runners scope breakpoint binding and frame
   * resolution with them (the configuration itself only knows the artifact).
   */
  @NotNull
  public static List<String> buildStepSourceDirectories(@NotNull RunConfiguration configuration) {
    String buildFilePath = configuration.getBeforeRunTasks().stream()
      .filter(Task.class::isInstance)
      .map(task -> ((Task)task).getBuildFilePath())
      .filter(path -> !path.isBlank())
      .findFirst()
      .orElse(null);
    if (buildFilePath == null) return List.of();
    return ReadAction.computeBlocking(
      () -> HaxeBuildClasspaths.sourceDirectories(configuration.getProject(), buildFilePath));
  }

  /** The build system's debug compile additions for the file's current selection, or null when it has none. */
  @Nullable
  static List<String> debugAdditions(@NotNull Project project, @NotNull String buildFilePath) {
    HaxeBuildFile buildFile = resolveBuildFile(project, buildFilePath);
    if (buildFile == null) return null;
    return ReadAction.computeBlocking(
      () -> HaxeBuildSystem.of(buildFile.type()).debugCompileAdditions(project, buildFile));
  }

  /** Debug additions for a single-run compile: always the plain haxe spelling for the selected target (see the call site). */
  @Nullable
  private static List<String> singleRunDebugAdditions(@NotNull Project project, @NotNull String buildFilePath) {
    HaxeBuildFile buildFile = resolveBuildFile(project, buildFilePath);
    if (buildFile == null) return null;
    return ReadAction.computeBlocking(() -> {
      HaxeTarget target = HaxeBuildSystem.of(buildFile.type()).launchTarget(project, buildFile);
      return target != null ? HaxeDebugAdditions.forTarget(target) : null;
    });
  }

  /** The path's typed build-file handle, or null when it resolves to no known build file. */
  @Nullable
  private static HaxeBuildFile resolveBuildFile(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(project, file);
    return type == null ? null : new HaxeBuildFile(file, type);
  }

  private static void notifyFailure(@NotNull Project project, @NotNull String message) {
    String title = HaxeDebuggerBundle.message("haxe.before.run.name");
    HaxeCommandNotifications.notify(project, title, message, NotificationType.ERROR);
  }
}
