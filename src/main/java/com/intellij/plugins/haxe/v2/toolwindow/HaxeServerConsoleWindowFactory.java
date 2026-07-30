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
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerListener;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager.ServerInfo;
import com.intellij.plugins.haxe.v2.buildtools.HaxeCompilationServerManager.ServerOutputListener;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.ui.HaxeBuildToolsConfigurable;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bottom tool window streaming the haxe compilation servers' stdio - startup
 * command, compile requests they serve, and any errors when clients connect.
 * Per-module SDKs mean several haxe versions can serve at once, so each server
 * instance gets its own tab (named after its SDK); tabs appear as instances
 * start and survive their server's restarts, replaying buffered history.
 */
public final class HaxeServerConsoleWindowFactory implements ToolWindowFactory, DumbAware {

  private static final String TOOLBAR_PLACE = "HaxeServerConsoleToolbar";
  /** Marks a tab with the server-instance id it renders; absent on the placeholder tab. */
  private static final Key<String> SERVER_ID = Key.create("haxe.server.console.server.id");

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    project.getMessageBus()
      .connect(toolWindow.getDisposable())
      .subscribe(HaxeCompilationServerListener.TOPIC, () -> syncTabs(project, toolWindow));
    // closing a tab means "done with that SDK's server": stop the process and
    // forget the instance (on project close the manager kills everything anyway,
    // so shutdown-time removals are harmless)
    toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
      @Override
      public void contentRemoved(@NotNull ContentManagerEvent event) {
        String serverId = event.getContent().getUserData(SERVER_ID);
        if (serverId != null) {
          HaxeCompilationServerManager.getInstance(project).removeServer(serverId);
        }
      }
    });
    syncTabs(project, toolWindow);
  }

  /** Mirrors the manager's instance list into tabs; runs on the EDT (the topic delivers there). */
  private static void syncTabs(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    ContentManager contentManager = toolWindow.getContentManager();
    var serverInfos = HaxeCompilationServerManager.getInstance(project).getServers();
    if (serverInfos.isEmpty()) {
      if (contentManager.getContentCount() == 0) {
        contentManager.addContent(createPlaceholderTab(project));
      }
      return;
    }
    removePlaceholder(contentManager);
    for (ServerInfo info : serverInfos) {
      if (findTab(contentManager, info.id()) == null) {
        contentManager.addContent(createServerTab(project, info));
      }
    }
  }

  @Nullable
  private static Content findTab(@NotNull ContentManager contentManager, @NotNull String serverId) {
    for (Content content : contentManager.getContents()) {
      if (serverId.equals(content.getUserData(SERVER_ID))) {
        return content;
      }
    }
    return null;
  }

  private static void removePlaceholder(@NotNull ContentManager contentManager) {
    for (Content content : contentManager.getContents()) {
      if (content.getUserData(SERVER_ID) == null) {
        contentManager.removeContent(content, true);
      }
    }
  }

  @NotNull
  private static Content createServerTab(@NotNull Project project, @NotNull ServerInfo info) {
    ConsoleView console = TextConsoleBuilderFactory.getInstance()
      .createBuilder(project)
      .getConsole();
    HaxeCompilationServerManager manager = HaxeCompilationServerManager.getInstance(project);
    ServerOutputListener listener =
      (text, outputType) -> console.print(text, ConsoleViewContentType.getConsoleViewType(outputType));
    manager.addOutputListener(info.id(), listener);

    DefaultActionGroup toolbarGroup = new DefaultActionGroup();
    toolbarGroup.add(new StartTabServerAction(project, info.id()));
    toolbarGroup.add(new RestartTabServerAction(project, info.id()));
    toolbarGroup.add(new StopTabServerAction(project, info.id()));
    Content content = ContentFactory.getInstance()
      .createContent(wrapWithToolbar(console, toolbarGroup), info.displayName(), false);
    content.putUserData(SERVER_ID, info.id());
    content.setCloseable(true);
    content.setDisposer(() -> {
      manager.removeOutputListener(info.id(), listener);
      Disposer.dispose(console);
    });
    return content;
  }

  /** Shown until the first server instance exists; offers starting the default SDK's server. */
  @NotNull
  private static Content createPlaceholderTab(@NotNull Project project) {
    ConsoleView console = TextConsoleBuilderFactory.getInstance()
      .createBuilder(project)
      .getConsole();
    boolean enabled = HaxeBuildToolSettings.getInstance(project).isCompilationServerEnabled();
    String hint = enabled ? HaxeBundle.message("haxe.server.console.hint.stopped")
                          : HaxeBundle.message("haxe.server.console.hint.disabled");
    console.print(hint + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);

    DefaultActionGroup toolbarGroup = new DefaultActionGroup();
    toolbarGroup.add(new StartDefaultServerAction(project));
    Content content = ContentFactory.getInstance()
      .createContent(wrapWithToolbar(console, toolbarGroup), "", false);
    content.setCloseable(false);
    content.setDisposer(() -> Disposer.dispose(console));
    return content;
  }

  @NotNull
  private static SimpleToolWindowPanel wrapWithToolbar(@NotNull ConsoleView console, @NotNull DefaultActionGroup group) {
    SimpleToolWindowPanel panel = new SimpleToolWindowPanel(false, true);
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, false);
    toolbar.setTargetComponent(panel);
    panel.setToolbar(toolbar.getComponent());
    panel.setContent(console.getComponent());
    return panel;
  }

  private static boolean isTabServerRunning(@NotNull Project project, @NotNull String serverId) {
    return HaxeCompilationServerManager.getInstance(project).getServers().stream()
      .anyMatch(info -> info.id().equals(serverId) && info.running());
  }

  private static final class StartDefaultServerAction extends DumbAwareAction {
    private final Project project;

    StartDefaultServerAction(@NotNull Project project) {
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

  private static final class StartTabServerAction extends DumbAwareAction {
    private final Project project;
    private final String serverId;

    StartTabServerAction(@NotNull Project project, @NotNull String serverId) {
      super(HaxeBundle.message("haxe.server.console.start"), null, AllIcons.Actions.Execute);
      this.project = project;
      this.serverId = serverId;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      // restartServer on a dead instance is a plain start; process creation must stay off the EDT
      AppExecutorUtil.getAppExecutorService()
        .execute(() -> HaxeCompilationServerManager.getInstance(project).restartServer(serverId));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(!isTabServerRunning(project, serverId));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }

  private static final class RestartTabServerAction extends DumbAwareAction {
    private final Project project;
    private final String serverId;

    RestartTabServerAction(@NotNull Project project, @NotNull String serverId) {
      super(HaxeBundle.message("haxe.server.console.restart"), null, AllIcons.Actions.Restart);
      this.project = project;
      this.serverId = serverId;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      // process creation must stay off the EDT
      AppExecutorUtil.getAppExecutorService()
        .execute(() -> HaxeCompilationServerManager.getInstance(project).restartServer(serverId));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(isTabServerRunning(project, serverId));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }

  private static final class StopTabServerAction extends DumbAwareAction {
    private final Project project;
    private final String serverId;

    StopTabServerAction(@NotNull Project project, @NotNull String serverId) {
      super(HaxeBundle.message("haxe.server.console.stop"), null, AllIcons.Actions.Suspend);
      this.project = project;
      this.serverId = serverId;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      HaxeCompilationServerManager.getInstance(project).stopServer(serverId);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(isTabServerRunning(project, serverId));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }
}
