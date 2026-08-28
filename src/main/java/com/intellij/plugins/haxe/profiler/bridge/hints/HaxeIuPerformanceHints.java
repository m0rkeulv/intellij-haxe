package com.intellij.plugins.haxe.profiler.bridge.hints;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.profiler.bridge.data.HaxeSamplingProfilerData;
import com.intellij.plugins.haxe.profiler.bridge.data.HaxeTracyProfilerData;
import com.intellij.profiler.PerformanceHintsManagerListener;
import com.intellij.profiler.api.ProfilerData;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gutter time hints for Haxe sources, driven by our own profiler
 * snapshots: when one opens in the profiler tool window, every open (and
 * later-opened) .hx editor whose file the capture touched gets a column of
 * rounded time chips ({@link HaxeLineChipGutter}) — per function for tracy
 * captures (zones carry the function's declaration line), per frame line
 * for sampled ones — red when a line's share of the session is high. The
 * stock In-Editor Performance Hints plugin only reads JFR snapshots, so
 * this is its Haxe counterpart on the same listener hook.
 */
public class HaxeIuPerformanceHints implements PerformanceHintsManagerListener {

  private static final Logger LOG = Logger.getInstance(HaxeIuPerformanceHints.class);
  private static final ExtensionPointName<PerformanceHintsManagerListener> EP_NAME =
    ExtensionPointName.create("com.intellij.profiler.performanceHints.listener");
  private static final String HINTS_VISIBLE_PROPERTY = "haxe.profiler.hints.visible";

  private final Map<Object, HaxeLineTimes> byTab = new HashMap<>();
  private final Map<Project, HaxeLineTimes> activeByProject = new HashMap<>();
  private final Map<Project, MessageBusConnection> connections = new HashMap<>();
  private final Map<Editor, HaxeLineChipGutter.Installed> annotated = new WeakHashMap<>();

  /** The registered listener instance, for the gutter show/hide action. */
  @Nullable
  static HaxeIuPerformanceHints getInstance() {
    return EP_NAME.findExtension(HaxeIuPerformanceHints.class);
  }

  /** The user's show/hide choice, kept across sessions and captures. */
  static boolean hintsVisible() {
    return PropertiesComponent.getInstance().getBoolean(HINTS_VISIBLE_PROPERTY, true);
  }

  static void setHintsVisible(boolean visible) {
    PropertiesComponent.getInstance().setValue(HINTS_VISIBLE_PROPERTY, visible, true);
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public void onProfilerDumpOpen(@NotNull ProfilerData data, @NotNull Project project, @NotNull Object tab) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      HaxeLineTimes lineTimes = lineTimesOf(data);
      if (lineTimes == null) return;
      ApplicationManager.getApplication().invokeLater(() -> {
        if (project.isDisposed()) return;
        byTab.put(tab, lineTimes);
        activate(project, lineTimes);
      });
    });
  }

  @Override
  public void onProfilerDumpClosed(@NotNull Project project, @NotNull Object tab) {
    HaxeLineTimes removed = byTab.remove(tab);
    if (removed != null && activeByProject.get(project) == removed) {
      activeByProject.remove(project);
      clearAnnotations();
    }
  }

  @Override
  public void onProfilerDumpSelectionChange(@NotNull Project project, @NotNull Object tab, boolean selected) {
    HaxeLineTimes lineTimes = byTab.get(tab);
    if (selected && lineTimes != null && activeByProject.get(project) != lineTimes) {
      activate(project, lineTimes);
    }
  }

  @Override
  public void onEventSelectionChange(@NotNull Project project, @NotNull Object tab, @NotNull String event) {
  }

  @Nullable
  private static HaxeLineTimes lineTimesOf(ProfilerData data) {
    try {
      if (data instanceof HaxeTracyProfilerData tracy) return HaxeLineTimes.fromStore(tracy.store());
      if (data instanceof HaxeSamplingProfilerData sampled) return HaxeLineTimes.fromSnapshot(sampled.snapshot());
    }
    catch (IOException e) {
      LOG.warn("could not aggregate line times for the gutter hints", e);
    }
    return null;
  }

  /** Whether the active capture has times for this file — drives the gutter action's visibility. */
  boolean hasHintsFor(@NotNull Project project, @NotNull VirtualFile file) {
    HaxeLineTimes active = activeByProject.get(project);
    if (active == null) return false;
    Map<Integer, HaxeLineTimes.LineTime> lines = active.forEditorPath(file.getPath());
    return lines != null && !lines.isEmpty();
  }

  /** Re-applies the show/hide choice to every open editor of every project with an active capture. */
  void applyHintsVisibility() {
    clearAnnotations();
    if (!hintsVisible()) return;
    activeByProject.forEach((project, lineTimes) -> {
      if (!project.isDisposed()) annotateOpenEditors(project, lineTimes);
    });
  }

  private void activate(Project project, HaxeLineTimes lineTimes) {
    activeByProject.put(project, lineTimes);
    clearAnnotations();
    annotateOpenEditors(project, lineTimes);
    connections.computeIfAbsent(project, this::listenForNewEditors);
  }

  private void annotateOpenEditors(Project project, HaxeLineTimes lineTimes) {
    for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
      if (fileEditor instanceof TextEditor textEditor) {
        annotate(textEditor.getEditor(), fileEditor.getFile(), lineTimes);
      }
    }
  }

  /** Editors opened while a capture is active get their column on open. */
  private MessageBusConnection listenForNewEditors(Project project) {
    MessageBusConnection connection = project.getMessageBus().connect();
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      @Override
      public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        HaxeLineTimes active = activeByProject.get(project);
        if (active != null && event.getNewEditor() instanceof TextEditor textEditor && event.getNewFile() != null) {
          annotate(textEditor.getEditor(), event.getNewFile(), active);
        }
      }
    });
    return connection;
  }

  private void annotate(Editor editor, @Nullable VirtualFile file, HaxeLineTimes lineTimes) {
    if (!hintsVisible()) return;
    if (file == null || annotated.containsKey(editor)) return;
    Map<Integer, HaxeLineTimes.LineTime> lines = lineTimes.forEditorPath(file.getPath());
    if (lines == null || lines.isEmpty()) return;
    HaxeLineChipGutter.Installed installed =
      HaxeLineChipGutter.install(editor, lines, lineTimes.sessionUs(), this::hintsClosedFromGutter);
    if (installed != null) {
      annotated.put(editor, installed);
    }
  }

  /** The gutter's own "Close Annotations" removed a chip column — honor it as Hide Performance Hints. */
  private void hintsClosedFromGutter() {
    if (!hintsVisible()) return;
    setHintsVisible(false);
    applyHintsVisibility();
  }

  private void clearAnnotations() {
    annotated.forEach((editor, installed) -> {
      if (!editor.isDisposed()) {
        installed.uninstall(editor);
      }
    });
    annotated.clear();
  }
}
