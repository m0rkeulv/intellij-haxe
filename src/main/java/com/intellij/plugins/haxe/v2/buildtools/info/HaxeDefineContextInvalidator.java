package com.intellij.plugins.haxe.v2.buildtools.info;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.lang.psi.stubs.HaxeStubableFileService;
import com.intellij.plugins.haxe.v2.buildtools.context.HaxeResolveContext;
import com.intellij.plugins.haxe.v2.buildtools.context.HaxeResolveContexts;
import com.intellij.util.FileContentUtilCore;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.concurrent.TimeUnit;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-indexes and reparses the haxe files whose parse depends on the define
 * context — those containing conditional compilation — after the context
 * changed. Files without it parse the same under every define set and are
 * left alone. Nothing is stored: every change invalidates every conditional
 * file, project and library alike, so two projects sharing libraries never
 * see each other's state.
 */
@CustomLog
final class HaxeDefineContextInvalidator {

  private HaxeDefineContextInvalidator() {
  }

  /**
   * Called from a pooled thread. The platform's per-file invalidation
   * re-indexes in the background and reloads only loaded PSI; open editors
   * additionally get the platform's forced reparse, the one event that
   * rebuilds their highlighting lexer.
   */
  static void invalidateConditionalFiles(@NotNull Project project) {
    List<VirtualFile> conditionalFiles = collectConditionalFiles(project);
    invalidateFiles(project, conditionalFiles);
    if (log.isDebugEnabled()) {
      log.debug("define context: " + conditionalFiles.size() + " conditional haxe files invalidated");
    }
  }

  /** The conditional files lexed under one of the build files; the others keep their trees. Pooled thread. */
  static void invalidateConditionalFilesOf(@NotNull Project project, @NotNull Set<String> buildFilePaths) {
    List<VirtualFile> conditionalFiles = collectConditionalFiles(project);
    List<VirtualFile> files = ReadAction.nonBlocking(() -> inContextOf(project, conditionalFiles, buildFilePaths))
      .expireWith(HaxeDefineContextService.getInstance(project))
      .executeSynchronously();
    invalidateFiles(project, files);
    if (log.isDebugEnabled()) {
      log.debug("define context: " + files.size() + " conditional haxe files of " + buildFilePaths.size() + " builds invalidated");
    }
  }

  /** The files whose resolve context is one of the build files. Call in a read action. */
  @NotNull
  static List<VirtualFile> inContextOf(@NotNull Project project, @NotNull List<VirtualFile> files, @NotNull Set<String> buildFilePaths) {
    HaxeResolveContexts contexts = HaxeResolveContexts.getInstance(project);
    return files.stream()
      .filter(file -> isInContextOf(contexts, file, buildFilePaths))
      .toList();
  }

  private static boolean isInContextOf(@NotNull HaxeResolveContexts contexts, @NotNull VirtualFile file, @NotNull Set<String> buildFilePaths) {
    HaxeResolveContext context = contexts.contextFor(file);
    return context != null && buildFilePaths.contains(context.path());
  }

  /** One file's context changed (pin, unpin, navigation): that file alone is re-indexed and, when open, reparsed. Any thread. */
  static void invalidateFile(@NotNull Project project, @NotNull VirtualFile file) {
    AppExecutorUtil.getAppExecutorService().execute(() -> invalidateFiles(project, List.of(file)));
  }

  /**
   * Pooled thread only: the index request must not run under the EDT's read
   * action. While the project is still opening nothing is write-safe (the
   * editor restoration pumps the queue inside read actions, which also trips
   * the platform's own reload the request posts), so the request waits for
   * the startup to pass.
   */
  private static void invalidateFiles(@NotNull Project project, @NotNull List<VirtualFile> files) {
    if (project.isDisposed()) return;
    if (!StartupManager.getInstance(project).postStartupActivityPassed()) {
      AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> invalidateFiles(project, files), 1, TimeUnit.SECONDS);
      return;
    }
    PushedFilePropertiesUpdater updater = PushedFilePropertiesUpdater.getInstance(project);
    Disposable expiry = HaxeDefineContextService.getInstance(project);
    for (VirtualFile file : files) {
      ReadAction.nonBlocking(() -> invalidate(updater, file))
        .expireWith(expiry)
        .executeSynchronously();
    }
    Runnable reparseOpenEditors = () -> reparseOpenEditors(project, files);
    ApplicationManager.getApplication().invokeLater(reparseOpenEditors, ModalityState.nonModal(), project.getDisposed());
  }

  /** Walks the indexable files without a lock — this is where an uncached stubable flag reads its file. */
  @NotNull
  private static List<VirtualFile> collectConditionalFiles(@NotNull Project project) {
    List<VirtualFile> conditionalFiles = new ArrayList<>();
    FileBasedIndex.getInstance().iterateIndexableFiles(file -> {
      if (isHaxeFile(file) && !HaxeStubableFileService.isStubable(file)) conditionalFiles.add(file);
      return true;
    }, project, null);
    return conditionalFiles;
  }

  /**
   * Idempotent, so a cancelled and retried read action is safe. Returns
   * Void so the lambda binds the Callable overload of nonBlocking (the
   * Runnable one is deprecated).
   */
  @Nullable
  private static Void invalidate(@NotNull PushedFilePropertiesUpdater updater, @NotNull VirtualFile file) {
    if (file.isValid()) {
      updater.filePropertiesChanged(file, HaxeDefineContextInvalidator::isHaxeFile);
    }
    return null;
  }

  private static void reparseOpenEditors(@NotNull Project project, @NotNull List<VirtualFile> conditionalFiles) {
    Set<VirtualFile> conditional = new HashSet<>(conditionalFiles);
    VirtualFile[] openFiles = FileEditorManager.getInstance(project).getOpenFiles();
    List<VirtualFile> openConditionalFiles = Arrays.stream(openFiles).filter(conditional::contains).toList();
    if (!openConditionalFiles.isEmpty()) {
      FileContentUtilCore.reparseFiles(openConditionalFiles);
    }
  }

  private static boolean isHaxeFile(@NotNull VirtualFile file) {
    return file.getFileType() == HaxeFileType.INSTANCE;
  }
}
