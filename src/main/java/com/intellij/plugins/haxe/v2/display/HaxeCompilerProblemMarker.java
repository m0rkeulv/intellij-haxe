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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * Marks the OTHER files in which a compile reported errors, through
 * {@link WolfTheProblemSolver}: their names turn red in the Project view and
 * editor tabs. A diagnostics response covers every file the compile touched.
 * The edited file's entry becomes editor annotations, and without these marks
 * a dependency's parse error would go unnoticed. A mark is cleared when a
 * later pass no longer reports errors in the file.
 */
@Service(Service.Level.PROJECT)
public final class HaxeCompilerProblemMarker {

  /** The external-source identity of these marks, so clearing them never clears another source's. */
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
   * Applies one diagnostics response. Project files other than the edited
   * one get marked when they have error-severity entries. Previously marked
   * files without errors in this response get cleared. Call on a background
   * thread.
   */
  public void updateFromDiagnostics(@NotNull String editedFilePath, @NotNull List<FileDiagnostics> results) {
    Map<String, VirtualFile> nowBroken = new HashMap<>();
    for (FileDiagnostics entry : results) {
      if (FileUtil.pathsEqual(entry.file(), editedFilePath)) continue;
      if (!hasErrorSeverity(entry)) continue;
      VirtualFile file = LocalFileSystem.getInstance().findFileByPath(entry.file());
      if (file == null || !file.isValid()) continue;
      // only project content is marked; library files are not the user's to fix
      boolean inContent = ReadAction.computeBlocking(() -> ProjectFileIndex.getInstance(project).isInContent(file));
      if (!inContent) continue;
      nowBroken.put(file.getPath(), file);
    }

    WolfTheProblemSolver problemSolver = WolfTheProblemSolver.getInstance(project);
    for (String path : markedPaths) {
      if (!nowBroken.containsKey(path)) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file != null) {
          problemSolver.clearProblemsFromExternalSource(file, SOURCE);
        }
        markedPaths.remove(path);
      }
    }
    for (Map.Entry<String, VirtualFile> broken : nowBroken.entrySet()) {
      problemSolver.reportProblemsFromExternalSource(broken.getValue(), SOURCE);
      markedPaths.add(broken.getKey());
    }
  }

  private static boolean hasErrorSeverity(@NotNull FileDiagnostics entry) {
    for (Diagnostic diagnostic : entry.diagnostics()) {
      if (diagnostic.severity() == DiagnosticSeverity.ERROR) return true;
    }
    return false;
  }
}
