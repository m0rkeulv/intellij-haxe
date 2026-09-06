package com.intellij.plugins.haxe.execution.console;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Links the {@code path line N} span of a Haxe stack frame to the file. Serves
 * the run console and the Analyze Stack Trace dialog alike.
 */
public final class HaxeStackTraceFilter implements Filter {

  private final Project project;
  private final GlobalSearchScope scope;

  public HaxeStackTraceFilter(@NotNull Project project, @NotNull GlobalSearchScope scope) {
    this.project = project;
    this.scope = scope;
  }

  @Override
  public @Nullable Result applyFilter(@NotNull String text, int entireLength) {
    HaxeStackFrameLine frame = HaxeStackFrameLine.parse(text);
    if (frame == null) return null;
    VirtualFile file = HaxeStackFrameFiles.find(project, scope, frame.path());
    if (file == null) return null;

    // Result offsets are document-absolute; the line starts entireLength minus its own length back
    int lineStart = entireLength - text.length();
    OpenFileHyperlinkInfo link = new OpenFileHyperlinkInfo(project, file, Math.max(0, frame.line() - 1));
    return new Result(lineStart + frame.pathStart(), lineStart + frame.lineEnd(), link);
  }
}
