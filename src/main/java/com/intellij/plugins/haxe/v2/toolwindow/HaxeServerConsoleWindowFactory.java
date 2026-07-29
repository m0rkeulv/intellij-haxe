package com.intellij.plugins.haxe.v2.toolwindow;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager.ServerOutputListener;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Bottom tool window streaming the haxe compilation server's stdio - startup
 * command, compile requests it serves, and any errors when clients connect.
 * Output arrives via the server manager's broadcast, so the console survives
 * server restarts and shows buffered history when opened late.
 */
public final class HaxeServerConsoleWindowFactory implements ToolWindowFactory, DumbAware {

  private static final String TOOLBAR_PLACE = "HaxeServerConsoleToolbar";

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    ConsoleView console = TextConsoleBuilderFactory.getInstance()
      .createBuilder(project)
      .getConsole();
    printStatusHint(project, console);

    HaxeCompilationServerManager manager = HaxeCompilationServerManager.getInstance(project);
    ServerOutputListener listener =
      (text, outputType) -> console.print(text, ConsoleViewContentType.getConsoleViewType(outputType));
    manager.addOutputListener(listener);

    SimpleToolWindowPanel panel = new SimpleToolWindowPanel(false, true);
    DefaultActionGroup toolbarGroup = new DefaultActionGroup();
    toolbarGroup.add(new StartServerAction(project));
    toolbarGroup.add(new StopServerAction(project));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarGroup, false);
    toolbar.setTargetComponent(panel);
    panel.setToolbar(toolbar.getComponent());
    panel.setContent(console.getComponent());

    Content content = ContentFactory.getInstance().createContent(panel, "", false);
    content.setDisposer(() -> {
      manager.removeOutputListener(listener);
      Disposer.dispose(console);
    });
    toolWindow.getContentManager().addContent(content);
  }

  private static void printStatusHint(@NotNull Project project, @NotNull ConsoleView console) {
    boolean enabled = HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled();
    boolean running = HaxeCompilationServerManager.getInstance(project).isRunning();
    String hint;
    if (!enabled) {
      hint = HaxeBundle.message("haxe.server.console.hint.disabled");
    }
    else if (!running) {
      hint = HaxeBundle.message("haxe.server.console.hint.stopped");
    }
    else {
      return; // running - the backlog replay carries the real output
    }
    console.print(hint + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
  }

  private static final class StartServerAction extends DumbAwareAction {
    private final Project project;

    StartServerAction(@NotNull Project project) {
      super(HaxeBundle.message("haxe.server.console.start"), null, AllIcons.Actions.Execute);
      this.project = project;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      // ensureRunning is a no-op returning -1 while the server is disabled in settings
      if (!HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled()) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, HaxeBuildToolsConfigurable.class);
        return;
      }
      // process creation must stay off the EDT
      AppExecutorUtil.getAppExecutorService()
        .execute(() -> HaxeCompilationServerManager.getInstance(project).ensureRunning(null));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(!HaxeCompilationServerManager.getInstance(project).isRunning());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }

  private static final class StopServerAction extends DumbAwareAction {
    private final Project project;

    StopServerAction(@NotNull Project project) {
      super(HaxeBundle.message("haxe.server.console.stop"), null, AllIcons.Actions.Suspend);
      this.project = project;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      HaxeCompilationServerManager.getInstance(project).stop();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(HaxeCompilationServerManager.getInstance(project).isRunning());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }
}
