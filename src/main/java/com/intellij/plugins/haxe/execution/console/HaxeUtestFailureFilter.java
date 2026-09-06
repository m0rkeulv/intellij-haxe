package com.intellij.plugins.haxe.execution.console;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links a utest assertion failure to its source: utest prints the failing
 * assertion's {@code haxe.PosInfos} as {@code src/ShapeTest.hx:174: expected 0
 * but it is "bogus"} — path, colon, line, colon, message. Only a path that
 * resolves to a file links, so other {@code word:123:} text stays plain.
 */
public final class HaxeUtestFailureFilter implements Filter {

  // utest failure line: "<path>.hx:<line>: <message>" at the start of the line (a drive
  // letter allowed in the path); a compiler message's ": characters 4-9" / ": lines 4-9"
  // tail is excluded, that line belongs to HaxeCompilerMessageFilter
  private static final Pattern UTEST_FAILURE =
    Pattern.compile("^\\s*(?<path>(?:\\w:)?[^:]+?\\.hx):(?<line>\\d+):\\s+(?!(?:characters|lines)\\s+\\d+-\\d+)\\S");

  private final Project project;
  private final GlobalSearchScope scope;

  public HaxeUtestFailureFilter(@NotNull Project project, @NotNull GlobalSearchScope scope) {
    this.project = project;
    this.scope = scope;
  }

  @Override
  public @Nullable Result applyFilter(@NotNull String text, int entireLength) {
    Matcher matcher = UTEST_FAILURE.matcher(text);
    if (!matcher.find()) return null;
    VirtualFile file = HaxeStackFrameFiles.find(project, scope, matcher.group("path"));
    if (file == null) return null;

    int line = Integer.parseInt(matcher.group("line"));
    // Result offsets are document-absolute; the line starts entireLength minus its own length back
    int lineStart = entireLength - text.length();
    OpenFileHyperlinkInfo link = new OpenFileHyperlinkInfo(project, file, Math.max(0, line - 1));
    return new Result(lineStart + matcher.start("path"), lineStart + matcher.end("line"), link);
  }
}
