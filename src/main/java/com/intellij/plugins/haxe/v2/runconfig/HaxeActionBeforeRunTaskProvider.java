package com.intellij.plugins.haxe.v2.runconfig;

import com.intellij.build.BuildDescriptor;
import com.intellij.build.BuildViewManager;
import com.intellij.build.DefaultBuildDescriptor;
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
import com.intellij.notification.NotificationGroupManager;
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
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInfo;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeBuildFileInspector;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompileCommands;
import com.intellij.plugins.haxe.v2.buildtools.HxmlProjects;
import com.intellij.plugins.haxe.v2.buildtools.LimeProjects;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFile;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileScanner;
import com.intellij.plugins.haxe.v2.toolwindow.tree.HaxeBuildFileType;
import com.intellij.util.PathUtil;
import icons.HaxeIcons;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
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

    private String buildFilePath = "";
    private String actionName = HxmlProjects.BUILD_ACTION;
    private String extraArguments = "";
    // opt-out for projects whose build files already carry the debug flags/lib
    private boolean injectDebugArguments = true;

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

    @Override
    public void writeExternal(@NotNull Element element) {
      super.writeExternal(element);
      JDOMExternalizerUtil.writeField(element, BUILD_FILE, buildFilePath);
      JDOMExternalizerUtil.writeField(element, ACTION, actionName);
      JDOMExternalizerUtil.writeField(element, ARGUMENTS, extraArguments);
      JDOMExternalizerUtil.writeField(element, INJECT_DEBUG, String.valueOf(injectDebugArguments));
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

    HaxeCompileCommands.Resolved resolved = ReadAction.computeBlocking(
      () -> HaxeCompileCommands.resolveAction(project, task.getBuildFilePath(), task.getActionName(), task.getExtraArguments()));
    if (resolved == null) {
      notifyFailure(project, HaxeDebuggerBundle.message("haxe.before.run.unresolvable", task.getBuildFilePath()));
      return false;
    }

    List<String> command = new ArrayList<>(resolved.command());
    if (debug && task.isInjectDebugArguments()) {
      List<String> additions = debugAdditions(project, task.getBuildFilePath());
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
    BuildProgress<BuildProgressDescriptor> progress = BuildViewManager.createBuildProgress(project);
    // the root progress takes its id FROM the descriptor - progress.getId()
    // asserts before start(), so the build id must be our own object
    DefaultBuildDescriptor buildDescriptor =
      new DefaultBuildDescriptor(new Object(), title, workDirectory, System.currentTimeMillis());
    buildDescriptor.setActivateToolWindowWhenAdded(true);
    progress.start(descriptorFor(title, buildDescriptor));

    try {
      GeneralCommandLine commandLine = new GeneralCommandLine(command).withWorkDirectory(resolved.workDirectory());
      // the process handler announces the command line as its first output -
      // printing it manually doubles the line
      CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
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
    catch (ExecutionException e) {
      progress.fail(System.currentTimeMillis(), StringUtil.notNullize(e.getMessage()));
      return false;
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

  /** Lime target ids compiled through hxcpp whose output the HXCPP (IntelliJ) debugger can attach to. */
  private static final List<String> LIME_DESKTOP_CPP_TARGETS = List.of("windows", "linux", "mac");

  /** The target's debug compile additions, or null when the file/target has none. Call in a read action. */
  @Nullable
  static List<String> debugAdditions(@NotNull Project project, @NotNull String buildFilePath) {
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildFilePath);
    if (file == null) return null;
    HaxeBuildFileType type = HaxeBuildFileScanner.detectType(file);
    if (type == HaxeBuildFileType.HXML) {
      HaxeBuildFileInfo info = ReadAction.computeBlocking(
        () -> HaxeBuildFileInspector.inspect(new HaxeBuildFile(file, HaxeBuildFileType.HXML)));
      return info.target() != null ? HaxeDebugAdditions.forTarget(info.target()) : null;
    }
    if (LimeProjects.isLimeFamily(type)) {
      // the lime tool takes -debug itself and forwards it into the haxe build it
      // generates - one flag covers every lime target
      List<String> additions = new ArrayList<>();
      additions.add("-debug");
      String targetFlag = LimeProjects.selectedTargetFlag(project, type, file);
      // hxcpp debugging needs the in-debuggee DAP server compiled in; lime's
      // --haxelib override merges the lib exactly like a project <haxelib>
      // entry (include.xml and extraParams included), so no project.xml edit.
      // Run builds never get this: additions apply only under the Debug executor.
      if (LIME_DESKTOP_CPP_TARGETS.contains(targetFlag)) {
        additions.add("--haxelib=intellij-hxcpp-debug-server");
      }
      return additions;
    }
    return null;
  }

  private static void notifyFailure(@NotNull Project project, @NotNull String message) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("haxe.command")
      .createNotification(HaxeDebuggerBundle.message("haxe.before.run.name"), message, NotificationType.ERROR)
      .notify(project);
  }
}
