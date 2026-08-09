package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeUnsavedDocuments;
import icons.HaxeIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Runs a tool command with its output attached to a console in the Run tool window,
 * so compile errors and long-running processes are fully visible (the console picks
 * up the plugin's error filters, giving clickable file:line links). Must be called
 * on the EDT. Used by the tool window's execute button and the build file action rows.
 */
public final class HaxeCommandRunner {

  private static final String NOTIFICATION_GROUP = "haxe.command";

  private HaxeCommandRunner() {
  }

  public static void run(@NotNull Project project,
                         @NotNull String presentableName,
                         @NotNull List<String> command,
                         @Nullable String workDirectory) {
    HaxeUnsavedDocuments.saveAll();
    GeneralCommandLine commandLine = new GeneralCommandLine(command)
      .withWorkDirectory(workDirectory != null ? workDirectory : project.getBasePath());
    try {
      KillableColoredProcessHandler processHandler = new KillableColoredProcessHandler(commandLine);
      ProcessTerminatedListener.attach(processHandler);

      // the process handler itself prints the command line on startNotify
      ConsoleView console = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project)
        .getConsole();
      console.attachToProcess(processHandler);

      RunContentDescriptor descriptor =
        new RunContentDescriptor(console, processHandler, console.getComponent(), presentableName, HaxeIcons.HAXE_LOGO);
      RunContentManager.getInstance(project)
        .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
      processHandler.startNotify();
    }
    catch (ExecutionException e) {
      NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(HaxeBundle.message("haxe.command.runner.failed", presentableName),
                            StringUtil.notNullize(e.getMessage()), NotificationType.ERROR)
        .notify(project);
    }
  }
}
