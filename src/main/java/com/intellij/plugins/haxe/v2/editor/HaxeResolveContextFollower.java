package com.intellij.plugins.haxe.v2.editor;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.HaxeFileType;
import com.intellij.plugins.haxe.util.HaxeReadActions;
import com.intellij.plugins.haxe.v2.buildtools.context.HaxeResolveContexts;
import com.intellij.plugins.haxe.v2.buildtools.info.HaxeDefineContextService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Carries the resolve context across navigation: a file opened from an
 * editor whose context reaches it takes that context
 * ({@link HaxeResolveContexts#follow}), so what the user lands on is what the
 * file they came from sees. Decided before the editor opens, so its
 * highlighting starts under the right context; the file's tree, possibly
 * parsed earlier under another one, is re-lexed once the editor is up. The
 * two hooks arrive on separate listener instances, so the file carries the
 * hand-over.
 */
// TODO: multiverse: EditorContextManager.setEditorContext on the opened editor replaces the follow - see doc/planned/resolve-context-plan.md, route B
public final class HaxeResolveContextFollower implements FileEditorManagerListener.Before, FileEditorManagerListener {

  private static final Key<Boolean> RELEX_ON_OPEN = Key.create("haxe.resolve.context.relex.on.open");

  private final Project project;

  public HaxeResolveContextFollower(@NotNull Project project) {
    this.project = project;
  }

  @Override
  public void beforeFileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    VirtualFile from = selectedFile(source);
    if (from == null || from.equals(file) || !isHaxe(from) || !isHaxe(file)) return;
    boolean changed = HaxeReadActions.compute(() -> HaxeResolveContexts.getInstance(project).follow(from, file));
    if (changed) {
      file.putUserData(RELEX_ON_OPEN, Boolean.TRUE);
    }
  }

  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    if (file.getUserData(RELEX_ON_OPEN) == null) return;
    file.putUserData(RELEX_ON_OPEN, null);
    HaxeDefineContextService.getInstance(project).fileContextChanged(file);
  }

  @Nullable
  private static VirtualFile selectedFile(@NotNull FileEditorManager source) {
    VirtualFile[] selected = source.getSelectedFiles();
    return selected.length == 0 ? null : selected[0];
  }

  private static boolean isHaxe(@NotNull VirtualFile file) {
    return FileTypeRegistry.getInstance().isFileOfType(file, HaxeFileType.INSTANCE);
  }
}
