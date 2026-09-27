package com.intellij.plugins.haxe.ide.formatter.hxformat;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Context-menu toggle on an hxformat.json: switches the hxformat integration
 * on (the scheme's opt-out also hides the status bar widget and the settings
 * page warning) and marks the file as the project's fallback formatting
 * config, used whenever no hxformat.json sits above a source file. A config
 * found by the normal upward search still wins, matching the CLI. Unchecking
 * only clears the fallback; the integration stays on (the settings checkbox
 * owns that).
 */
public final class HxformatUseAsRulesAction extends ToggleAction implements DumbAware {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    e.getPresentation().setEnabledAndVisible(configFile(e) != null);
  }

  @Override
  public boolean isSelected(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    VirtualFile file = configFile(e);
    if (project == null || file == null) return false;
    return file.getUrl().equals(HxformatConfigs.getInstance(project).overrideConfigUrl());
  }

  @Override
  public void setSelected(@NotNull AnActionEvent e, boolean state) {
    Project project = e.getProject();
    VirtualFile file = configFile(e);
    if (project == null || file == null) return;
    HxformatConfigs configs = HxformatConfigs.getInstance(project);
    if (state) {
      configs.setIntegrationEnabled(true);
    }
    configs.setOverrideConfigUrl(state ? file.getUrl() : null);
  }

  @Nullable
  private static VirtualFile configFile(@NotNull AnActionEvent e) {
    if (e.getProject() == null) return null;
    VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
    boolean isConfig = file != null && !file.isDirectory()
                       && HxformatConfigs.HXFORMAT_FILE_NAME.equals(file.getName());
    return isConfig ? file : null;
  }
}
