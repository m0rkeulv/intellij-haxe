package com.intellij.plugins.haxe.runner.debugger.flash;

import com.intellij.execution.ExecutionException;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.plugins.haxe.HaxeBundle;

/**
 * Presence check for the optional Flash/Flex plugin, which drives every
 * flash-family debug session. Callers must pass this gate BEFORE classloading
 * anything that references the plugin's classes ({@code HaxeFlashDebuggingUtil}
 * and below), or a flex-less IDE throws NoClassDefFoundError instead of the
 * readable message.
 */
public final class FlexPluginGate {

  private static final PluginId FLEX_PLUGIN_ID = PluginId.getId("com.intellij.flex");

  private FlexPluginGate() {
  }

  public static void requireFlexPlugin() throws ExecutionException {
    if (!PluginManagerCore.isLoaded(FLEX_PLUGIN_ID)) {
      throw new ExecutionException(HaxeBundle.message(
        PluginManagerCore.isDisabled(FLEX_PLUGIN_ID) ? "enable.flex.plugin" : "install.flex.plugin"));
    }
  }
}
