package com.intellij.plugins.haxe.runner.debugger.browser;

import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.ide.BrowserUtil;
import com.intellij.plugins.haxe.HaxeDebuggerBundle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plain Run for a browser configuration: no debugger, no adapter, no node —
 * just the content served and a browser pointed at it. In serve mode the
 * built-in http server hosts the content directory and LIVES UNTIL THE USER
 * PRESSES STOP (the synthetic process handler's lifetime is the server's);
 * in url mode the browser simply opens the configured address.
 *
 * The browser itself is fire-and-forget: browsers reuse an existing instance
 * and detach immediately, so its process is meaningless as a lifetime anchor.
 */
public class BrowserRunningState implements RunProfileState {
  private final BrowserRunConfiguration configuration;

  public BrowserRunningState(BrowserRunConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public @Nullable ExecutionResult execute(Executor executor, @NotNull ProgramRunner<?> runner) throws ExecutionException {
    ContentHttpServer server = null;
    String url;
    try {
      if (configuration.isServeContent()) {
        Path contentRoot = configuration.resolveContentRootOrNull();
        if (contentRoot == null) {
          throw new ExecutionException(HaxeDebuggerBundle.message("browser.runner.no.content.root"));
        }
        server = new ContentHttpServer(contentRoot);
        url = server.getBaseUrl();
      } else {
        url = configuration.getUrl();
      }
    } catch (IOException e) {
      throw new ExecutionException(HaxeDebuggerBundle.message("browser.runner.server.failed", e.getMessage()), e);
    }

    try {
      openBrowser(url);
    } catch (IOException | RuntimeException e) {
      if (server != null) {
        server.close();
      }
      throw new ExecutionException(HaxeDebuggerBundle.message("browser.runner.browser.failed", e.getMessage()), e);
    }

    ConsoleView console = TextConsoleBuilderFactory.getInstance()
      .createBuilder(configuration.getProject()).getConsole();
    ServerLifetimeHandler handler = new ServerLifetimeHandler(server,
      buildBanner(server, url));
    console.attachToProcess(handler);
    return new DefaultExecutionResult(console, handler);
  }

  private String buildBanner(@Nullable ContentHttpServer server, String url) {
    StringBuilder banner = new StringBuilder();
    if (server != null) {
      banner.append(HaxeDebuggerBundle.message("browser.runner.serving",
                                               configuration.getContentRoot(), url)).append('\n');
      banner.append(HaxeDebuggerBundle.message("browser.runner.stop.hint")).append('\n');
    } else {
      banner.append(HaxeDebuggerBundle.message("browser.runner.opened", url)).append('\n');
    }
    return banner.toString();
  }

  // The configured executable gets the url as its argument (works for every
  // browser fork); blank falls back to the system default browser.
  private void openBrowser(String url) throws IOException {
    String executable = configuration.getBrowserExecutablePath();
    if (executable.isBlank()) {
      BrowserUtil.browse(url);
      return;
    }
    try {
      new GeneralCommandLine(executable, url).createProcess();
    } catch (ExecutionException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /**
   * A synthetic process whose lifetime IS the content server's: alive until
   * Stop, which closes the server and ends the run session. With no server
   * (url mode) it just gives the session something to stop.
   */
  private static final class ServerLifetimeHandler extends ProcessHandler {
    private final @Nullable ContentHttpServer server;
    private final String banner;

    ServerLifetimeHandler(@Nullable ContentHttpServer server, String banner) {
      this.server = server;
      this.banner = banner;
    }

    @Override
    public void startNotify() {
      super.startNotify();
      notifyTextAvailable(banner, ProcessOutputTypes.SYSTEM);
    }

    @Override
    protected void destroyProcessImpl() {
      stopServer();
      notifyProcessTerminated(0);
    }

    @Override
    protected void detachProcessImpl() {
      stopServer();
      notifyProcessDetached();
    }

    private void stopServer() {
      if (server != null) {
        server.close();
      }
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
}
