/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.runner.debugger;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.lang.javascript.flex.projectStructure.model.FlexBuildConfiguration;
import com.intellij.lang.javascript.flex.run.BCBasedRunnerParameters;
import com.intellij.lang.javascript.flex.run.FlashRunnerParameters;
import com.intellij.lang.javascript.flex.run.LauncherParameters;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSessionStartedResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Debug-session entry points for the flash family, built on the Flex plugin's
 * fdb-driven {@link com.intellij.lang.javascript.flex.debug.FlexDebugProcess}.
 * Everything here touches Flex plugin classes, so callers must gate on the
 * plugin's presence BEFORE classloading this type.
 */
public class HaxeFlashDebuggingUtil {

  /**
   * A Flash (swf) debug session: fdb launches the swf itself — with the given
   * standalone player when one resolves, else the OS default handler.
   */
  public static RunContentDescriptor getDescriptor(final Module module,
                                                   ExecutionEnvironment env,
                                                   String urlToLaunch,
                                                   String flexSdkName,
                                                   @Nullable String flashPlayerPath) throws ExecutionException {
    final Sdk flexSdk = FlexSdkUtils.findFlexOrFlexmojosSdk(flexSdkName);
    if (flexSdk == null) {
      throw new ExecutionException(HaxeBundle.message("flex.sdk.not.found", flexSdkName));
    }

    final FlexBuildConfiguration bc = new FakeFlexBuildConfiguration(flexSdk, urlToLaunch);

    XDebugProcessStarter starter = new XDebugProcessStarter() {
      @NotNull
      public XDebugProcess start(@NotNull final XDebugSession session) throws ExecutionException {
        try {
          final FlashRunnerParameters params = new FlashRunnerParameters();
          params.setModuleName(module.getName());
          if (flashPlayerPath != null && !flashPlayerPath.isBlank()) {
            // the 4-arg constructor requires a non-null browser even for the
            // Player type; the default instance + setters avoid it
            LauncherParameters playerLauncher = new LauncherParameters();
            playerLauncher.setLauncherType(LauncherParameters.LauncherType.Player);
            playerLauncher.setPlayerPath(flashPlayerPath);
            params.setLauncherParameters(playerLauncher);
          }
          return new HaxeDebugProcess(session, bc, params);
        }
        catch (IOException e) {
          throw new ExecutionException(e.getMessage(), e);
        }
      }
    };
    return startSession(module, env, starter).getRunContentDescriptor();
  }

  /** The split-debugger-safe session start (XDebugSession's own getRunContentDescriptor is deprecated and logs an error). */
  @NotNull
  private static XSessionStartedResult startSession(@NotNull Module module,
                                                    @NotNull ExecutionEnvironment env,
                                                    @NotNull XDebugProcessStarter starter) throws ExecutionException {
    return XDebuggerManager.getInstance(module.getProject())
      .newSessionBuilder(starter)
      .environment(env)
      .startSession();
  }

  /**
   * An AIR debug session: fdb only WAITS (the plain {@link BCBasedRunnerParameters}
   * route the Flex plugin uses for remote debugging), and adl is spawned here with
   * the given command line. The Flex plugin's own desktop path cannot launch a
   * lime-packaged app — it derives the descriptor location and content root from
   * one output path, but lime puts the descriptor at the export root with the
   * content in {@code bin/} beside it.
   */
  public static RunContentDescriptor getAirDescriptor(final Module module,
                                                      ExecutionEnvironment env,
                                                      String flexSdkName,
                                                      @NotNull GeneralCommandLine adlCommandLine) throws ExecutionException {
    final Sdk flexSdk = FlexSdkUtils.findFlexOrFlexmojosSdk(flexSdkName);
    if (flexSdk == null) {
      throw new ExecutionException(HaxeBundle.message("flex.sdk.not.found", flexSdkName));
    }

    final FlexBuildConfiguration bc = new FakeFlexBuildConfiguration(flexSdk, adlCommandLine.getExePath());

    XDebugProcessStarter starter = new XDebugProcessStarter() {
      @NotNull
      public XDebugProcess start(@NotNull final XDebugSession session) throws ExecutionException {
        try {
          BCBasedRunnerParameters params = new BCBasedRunnerParameters();
          params.setModuleName(module.getName());
          return new HaxeDebugProcess(session, bc, params);
        }
        catch (IOException e) {
          throw new ExecutionException(e.getMessage(), e);
        }
      }
    };
    XSessionStartedResult started = startSession(module, env, starter);

    // fdb is up and waiting; the -debug swf connects to it as the app starts
    OSProcessHandler adlHandler = new OSProcessHandler(adlCommandLine);
    tieTogether(started.getSession(), adlHandler);
    adlHandler.startNotify();

    return started.getRunContentDescriptor();
  }

  /** The app closing ends the session; the session stopping kills the app. */
  private static void tieTogether(@NotNull XDebugSession debugSession, @NotNull OSProcessHandler adlHandler) {
    adlHandler.addProcessListener(new ProcessListener() {
      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        debugSession.stop();
      }
    });
    debugSession.addSessionListener(new XDebugSessionListener() {
      @Override
      public void sessionStopped() {
        if (!adlHandler.isProcessTerminated()) {
          adlHandler.destroyProcess();
        }
      }
    });
  }
}
