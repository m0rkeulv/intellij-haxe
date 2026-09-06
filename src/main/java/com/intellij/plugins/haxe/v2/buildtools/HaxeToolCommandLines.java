package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PtyCommandLine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Command lines for tool runs shown in a console. They run under a
 * pseudo-terminal: a tool that prompts through the console device (hxcpp's
 * {@code Sys.getChar}) only sees typed answers that way, a stdin pipe never
 * reaches it. Console mode keeps stderr separate and leaves the echo of
 * typed text to the console view.
 */
public final class HaxeToolCommandLines {

  private HaxeToolCommandLines() {
  }

  @NotNull
  public static GeneralCommandLine interactive(@NotNull List<String> command, @Nullable String workDirectory) {
    return new PtyCommandLine(command)
      .withInitialColumns(PtyCommandLine.MAX_COLUMNS)
      .withWorkDirectory(workDirectory);
  }
}
