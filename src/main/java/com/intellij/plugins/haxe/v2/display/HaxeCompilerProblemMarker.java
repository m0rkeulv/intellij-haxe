package com.intellij.plugins.haxe.v2.display;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.display.protocol.Diagnostic;
import com.intellij.plugins.haxe.display.protocol.DiagnosticSeverity;
import com.intellij.plugins.haxe.display.protocol.FileDiagnostics;
import com.intellij.problems.WolfTheProblemSolver;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Marks OTHER files a compile reported errors in (red file names in the
 * Project view and editor tabs, via {@link WolfTheProblemSolver}). A
 * diagnostics response covers every file the compile touched — the entry for
 * the edited file becomes editor annotations, but a dependency's parse error
 * would otherwise vanish silently. Files recover when a later pass no longer
 * reports them.
 */
@Service(Service.Level.PROJECT)
public final class HaxeCompilerProblemMarker {

  /** Identity handed to the wolf so our marks never clear another source's. */
  private static final Object SOURCE = new Object();

  private final Project project;
  private final Set<String> markedPaths = ConcurrentHashMap.newKeySet();

  public HaxeCompilerProblemMarker(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  public static HaxeCompilerProblemMarker getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilerProblemMarker.class);
  }

  /**
   * Applies one diagnostics response: project files (other than the edited
   * one) with error-severity entries get marked, previously marked files
   * absent from the response get cleared. Call on a background thread.
   */
  public void updateFromDiagnostics(@NotNull String editedFilePath, @NotNull List<FileDiagnostics> results) {
    Set<String> nowBroken = new HashSet<>();
    Map<String, VirtualFile> resolved = new ConcurrentHashMap<>();
    for (FileDiagnostics entry : results) {
      if (FileUtil.pathsEqual(entry.file(), editedFilePath)) continue;
      if (!hasErrorSeverity(entry)) continue;
      VirtualFile file = LocalFileSystem.getInstance().findFileByPath(entry.file());
      if (file == null || !file.isValid()) continue;
      // library files are not the user's problem to fix - only mark project content
      boolean inContent = ReadAction.computeBlocking(() -> ProjectFileIndex.getInstance(project).isInContent(file));
      if (!inContent) continue;
      nowBroken.add(file.getPath());
      resolved.put(file.getPath(), file);
    }

    WolfTheProblemSolver wolf = WolfTheProblemSolver.getInstance(project);
    for (String path : markedPaths) {
      if (!nowBroken.contains(path)) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file != null) {
          wolf.clearProblemsFromExternalSource(file, SOURCE);
        }
        markedPaths.remove(path);
      }
    }
    for (String path : nowBroken) {
      wolf.reportProblemsFromExternalSource(resolved.get(path), SOURCE);
      markedPaths.add(path);
    }
  }

  private static boolean hasErrorSeverity(@NotNull FileDiagnostics entry) {
    for (Diagnostic diagnostic : entry.diagnostics()) {
      if (diagnostic.severity() == DiagnosticSeverity.ERROR) return true;
    }
    return false;
  }
}
