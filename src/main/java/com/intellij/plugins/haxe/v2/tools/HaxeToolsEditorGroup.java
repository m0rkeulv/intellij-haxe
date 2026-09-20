package com.intellij.plugins.haxe.v2.tools;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.v2.buildtools.HaxeToolConfigs;
import org.jetbrains.annotations.NotNull;

/**
 * The editor's "Haxe Tools" submenu: shown only for a Haxe file that has at
 * least one tool config (checkstyle.json / hxformat.json) in scope, so the
 * menu never renders empty.
 */
public final class HaxeToolsEditorGroup extends DefaultActionGroup implements DumbAware {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    VirtualFile file = HaxeFileToolAction.haxeFile(e);
    boolean anyToolAvailable = project != null && file != null
      && (HaxeToolConfigs.findConfigDirectory(project, file, HaxeToolConfigs.FORMATTER_CONFIG_NAME) != null
          || HaxeToolConfigs.findConfigDirectory(project, file, HaxeToolConfigs.CHECKSTYLE_CONFIG_NAME) != null);
    e.getPresentation().setEnabledAndVisible(anyToolAvailable);
  }
}
