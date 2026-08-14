package com.intellij.plugins.haxe.runner.debugger.dap.ide;

import java.util.List;

/**
 * The path test behind breakpoint/source scoping: backends whose debuggee
 * matches files by relative name restrict themselves to the build's source
 * directories with it.
 */
public final class DapSourceScopes {

  private DapSourceScopes() {
  }

  /** Whether the VFS path lies under any of the directories (a directory-name PREFIX is not a match). */
  public static boolean underAny(String vfsPath, List<String> directories) {
    return directories.stream().anyMatch(directory -> vfsPath.startsWith(directory + "/"));
  }
}
