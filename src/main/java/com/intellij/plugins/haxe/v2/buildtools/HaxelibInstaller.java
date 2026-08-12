package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.haxelib.HaxelibSemVer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Installs haxelib libraries without touching haxelib's selected version.
 * Runs external processes - call on a background thread.
 */
public final class HaxelibInstaller {

  private static final int INSTALL_TIMEOUT_MS = 600_000;

  private HaxelibInstaller() {
  }

  /**
   * Runs one {@code haxelib install} (restoring the previously selected version
   * when the install would hijack it); null on success, else the failure detail.
   * {@code version} is the declared/pinned version, {@code resolvedVersion} the
   * version haxelib currently selects.
   */
  @Nullable
  public static String install(@NotNull Project project, @NotNull String name,
                               @Nullable String version, @Nullable String resolvedVersion) {

    GeneralCommandLine commandLine = haxelibCommand(project, installParameters(name, version));

    try {
      ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(INSTALL_TIMEOUT_MS);
      if (output.getExitCode() != 0 || output.isTimeout()) {
        return StringUtil.trimTrailing(output.getStdout() + "\n" + output.getStderr());
      }

      restoreSelectedVersion(project, name, version, resolvedVersion);
      return null;
    }

    catch (ExecutionException e) {
      return StringUtil.notNullize(e.getMessage());
    }
  }

  @NotNull
  private static GeneralCommandLine haxelibCommand(@NotNull Project project, @NotNull List<String> parameters) {
    return new GeneralCommandLine()
      .withExePath(HaxeToolPathResolver.resolveHaxelibExecutable(project))
      .withParameters(parameters)
      .withWorkDirectory(project.getBasePath());
  }

  // "haxelib install name [version] --always"; git/path pseudo-versions cannot be passed to install
  @NotNull
  private static List<String> installParameters(@NotNull String name, @Nullable String version) {
    var parameters = new ArrayList<>(List.of("install", name));
    if (HaxelibSemVer.isReleaseVersion(version)) {
      parameters.add(version);
    }
    parameters.add("--always");
    return parameters;
  }

  /**
   * Installing a pinned version makes it haxelib's SELECTED version as a side
   * effect, silently switching every unpinned project. Restore the previous
   * selection ("dev"/"git" pseudo-versions cannot be re-set and stay put).
   */
  private static void restoreSelectedVersion(@NotNull Project project, @NotNull String name,
                                             @Nullable String version, @Nullable String previous) {
    boolean selectionHijacked = HaxelibSemVer.isReleaseVersion(version)
                                && HaxelibSemVer.isReleaseVersion(previous)
                                && !previous.equals(version);
    if (!selectionHijacked) return;

    GeneralCommandLine commandLine = haxelibCommand(project, List.of("set", name, previous, "--always"));
    try {
      new CapturingProcessHandler(commandLine).runProcess(INSTALL_TIMEOUT_MS);
    }
    catch (ExecutionException e) {
      // the install itself succeeded; a failed restore only leaves the new version selected
    }
  }
}
