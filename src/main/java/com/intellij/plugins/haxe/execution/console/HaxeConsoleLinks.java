package com.intellij.plugins.haxe.execution.console;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** The link a console filter answers with: a span of one console line opening a file at a position. */
final class HaxeConsoleLinks {

  private HaxeConsoleLinks() {
  }

  /**
   * Links {@code [spanStart, spanEnd)} of the line {@code text} to {@code file} at the
   * 0-based {@code documentLine}/{@code documentColumn}. Result offsets are
   * document-absolute; the line starts {@code entireLength} minus its own length back.
   */
  @NotNull
  static Filter.Result link(@NotNull Project project,
                            @NotNull VirtualFile file,
                            int documentLine,
                            int documentColumn,
                            @NotNull String text,
                            int entireLength,
                            int spanStart,
                            int spanEnd) {
    int lineStart = entireLength - text.length();
    OpenFileHyperlinkInfo info = new OpenFileHyperlinkInfo(project, file, documentLine, documentColumn);
    return new Filter.Result(lineStart + spanStart, lineStart + spanEnd, info);
  }
}
